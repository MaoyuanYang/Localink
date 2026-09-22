package com.localink.framework.reconcile;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.config.ReconcileProperties;
import com.localink.constant.KeyManage;
import com.localink.entity.RollbackFailureLog;
import com.localink.entity.VoucherOrder;
import com.localink.entity.VoucherReconcileLog;
import com.localink.mapper.RollbackFailureLogMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mapper.VoucherReconcileLogMapper;
import com.localink.service.VoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 对账任务（M5-A）：M3.12 三层流水数据地基的回报兑现。
 *
 * <p>单向比对（Redis 流水 → DB）：Redis 是活账本（TTL 跟随活动、只存最新态），DB 是长账——
 * 差异的唯一危险形态是"Redis 扣了但 DB 无单"（消息丢失/建单链路死亡，用户资格被占死）；
 * 反向（DB 有单 Redis 无流水）不构成正确性风险（Redis 只是缓存性质态），不比对。</p>
 *
 * <p>单遍流程：SCAN 流水 key → 逐笔比对（logType=2 已恢复即闭环；logType=1 过宽限期且 DB
 * 扣减行缺失 = 差异 → 回滚资格补偿；有行 = 一致 → 待处理状态翻"一致"）→ 回滚失败表重试收敛。</p>
 */
@Slf4j
@Component
@EnableConfigurationProperties(com.localink.config.ReconcileProperties.class)
public class ReconciliationJob {

    private static final String FLOW_KEY_MARKER = "seckill:flow:";
    private static final int STATUS_CONSISTENT = 4;

    private final StringRedisTemplate redisTemplate;
    private final KeyBuilder keyBuilder;
    private final VoucherReconcileLogMapper reconcileLogMapper;
    private final VoucherOrderMapper orderMapper;
    private final RollbackFailureLogMapper rollbackFailureLogMapper;
    private final VoucherOrderService voucherOrderService;
    private final ReconcileProperties properties;

    public ReconciliationJob(StringRedisTemplate redisTemplate,
                             KeyBuilder keyBuilder,
                             VoucherReconcileLogMapper reconcileLogMapper,
                             VoucherOrderMapper orderMapper,
                             RollbackFailureLogMapper rollbackFailureLogMapper,
                             VoucherOrderService voucherOrderService,
                             ReconcileProperties properties) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
        this.reconcileLogMapper = reconcileLogMapper;
        this.orderMapper = orderMapper;
        this.rollbackFailureLogMapper = rollbackFailureLogMapper;
        this.voucherOrderService = voucherOrderService;
        this.properties = properties;
    }

    /**
     * 定时对账（fixedDelay：上一轮完成后再计时，天然防重叠）。测试直调 {@link #runOnce()}。
     */
    @Scheduled(fixedDelayString = "${localink.reconcile.interval-ms:300000}")
    public void scheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        runOnce();
    }

    /**
     * 单遍对账。返回处理的差异数（观测口径，测试断言用）。
     */
    public int runOnce() {
        int compensated = 0;
        int flipped = 0;
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(flowPattern()).count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long voucherId = parseVoucherId(key);
                if (voucherId == null) {
                    continue;
                }
                for (Map.Entry<Object, Object> entry : redisTemplate.opsForHash().entries(key).entrySet()) {
                    JSONObject flow = JSONObject.parseObject(String.valueOf(entry.getValue()));
                    if (flow.getIntValue("logType") == 2) {
                        flipped += settleRestoredTrace(flow.getLongValue("traceId"));
                        continue;
                    }
                    Long traceId = flow.getLongValue("traceId");
                    Long userId = flow.getLongValue("userId");
                    VoucherReconcileLog deductRow = reconcileLogMapper.selectOne(
                            new LambdaQueryWrapper<VoucherReconcileLog>()
                                    .eq(VoucherReconcileLog::getTraceId, traceId)
                                    .eq(VoucherReconcileLog::getLogType, 1)
                                    .last("LIMIT 1"));
                    if (deductRow == null) {
                        // 宽限期只保护差异判定（DB 无行可能是建单在途，宁晚勿误）；
                        // 一致确认（有行）不受限——翻牌无害，随到随翻
                        if (withinGrace(flow.getLongValue("ts"))) {
                            continue;
                        }
                        compensate(voucherId, userId, traceId);
                        compensated++;
                    } else {
                        flipped += settleConsistentTrace(deductRow);
                    }
                }
            }
        }
        retryRollbackFailures();
        log.info("对账完成: 差异补偿={}笔, 状态翻牌={}笔", compensated, flipped);
        return compensated;
    }

    /**
     * 一致闭环：扣减行所在事务的订单必然存在——两者待处理状态一并翻"一致"。
     */
    private int settleConsistentTrace(VoucherReconcileLog deductRow) {
        int flipped = 0;
        if (deductRow.getReconciliationStatus() != null
                && deductRow.getReconciliationStatus() == 1) {
            reconcileLogMapper.update(null, new LambdaUpdateWrapper<VoucherReconcileLog>()
                    .eq(VoucherReconcileLog::getId, deductRow.getId())
                    .set(VoucherReconcileLog::getReconciliationStatus, STATUS_CONSISTENT));
            flipped++;
        }
        VoucherOrder order = orderMapper.selectById(deductRow.getOrderId());
        if (order != null && order.getReconciliationStatus() != null
                && order.getReconciliationStatus() == 1) {
            orderMapper.update(null, new LambdaUpdateWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getId, order.getId())
                    .set(VoucherOrder::getReconciliationStatus, STATUS_CONSISTENT));
            flipped++;
        }
        return flipped;
    }

    /**
     * 已恢复（logType=2）的流水：闭环完成，把恢复行的待处理状态翻"一致"（订单可能不存在——
     * 恢复行本就来自"没建成单"的路径， orderId 由 traceId 顶替，不查订单）。
     */
    private int settleRestoredTrace(long traceId) {
        VoucherReconcileLog restoreRow = reconcileLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getTraceId, traceId)
                        .eq(VoucherReconcileLog::getLogType, 2)
                        .last("LIMIT 1"));
        if (restoreRow == null || restoreRow.getReconciliationStatus() == null
                || restoreRow.getReconciliationStatus() != 1) {
            return 0;
        }
        reconcileLogMapper.update(null, new LambdaUpdateWrapper<VoucherReconcileLog>()
                .eq(VoucherReconcileLog::getId, restoreRow.getId())
                .set(VoucherReconcileLog::getReconciliationStatus, STATUS_CONSISTENT));
        return 1;
    }

    /**
     * 差异补偿：Redis 扣了但 DB 无单（过宽限期）——回滚资格（逆增量+流水翻恢复+DB 恢复行，
     * 幂等可重试）。补偿即告警：error 日志带全上下文，人工只需处理补偿自身再失败的场景（落失败表）。
     */
    private void compensate(Long voucherId, Long userId, Long traceId) {
        log.error("对账差异[告警]: Redis 已扣减但 DB 无订单/流水, 执行资格回滚补偿, voucherId={}, userId={}, traceId={}",
                voucherId, userId, traceId);
        voucherOrderService.rollbackSeckillQualification(voucherId, userId, traceId, traceId,
                "RECONCILE", "redis deducted but no db row, grace exceeded");
    }

    /**
     * 回滚失败表重试：先删旧行再走统一回滚入口——成功即收敛（Lua"无需补偿"或完成都不落新行）；
     * 失败则入口自动落新行回到表内。行龄超过阈值仍存在 = 长期不收敛，error 告警人工介入。
     */
    private void retryRollbackFailures() {
        LocalDateTime alarmBefore = LocalDateTime.now().minusHours(properties.getFailureAgeAlarmHours());
        for (RollbackFailureLog failure : rollbackFailureLogMapper.selectList(null)) {
            if (failure.getCreateTime() != null && failure.getCreateTime().isBefore(alarmBefore)) {
                log.error("回滚失败行长期未收敛[告警]: id={}, voucherId={}, userId={}, attempts={}, created={}",
                        failure.getId(), failure.getVoucherId(), failure.getUserId(),
                        failure.getRetryAttempts(), failure.getCreateTime());
            }
            rollbackFailureLogMapper.deleteById(failure.getId());
            try {
                voucherOrderService.rollbackSeckillQualification(failure.getVoucherId(),
                        failure.getUserId(), failure.getOrderId(), failure.getTraceId(),
                        "RETRY", "reconcile retry of source=" + failure.getSource());
            } catch (Exception e) {
                log.error("回滚失败表重试异常, 行已删除, 失败入口已重新落表, voucherId={}, userId={}",
                        failure.getVoucherId(), failure.getUserId(), e);
            }
        }
    }

    private boolean withinGrace(long ts) {
        return System.currentTimeMillis() - ts < properties.getGraceMinutes() * 60_000;
    }

    private String flowPattern() {
        String sample = keyBuilder.build(KeyManage.SECKILL_FLOW, 0L).getKey();
        int markerIndex = sample.indexOf(FLOW_KEY_MARKER);
        return sample.substring(0, markerIndex + FLOW_KEY_MARKER.length()) + "*";
    }

    /**
     * "lk:seckill:flow:{123}" → 123（剥 hash tag 花括号）。
     */
    private Long parseVoucherId(String key) {
        int markerIndex = key.indexOf(FLOW_KEY_MARKER);
        if (markerIndex < 0) {
            return null;
        }
        String tail = key.substring(markerIndex + FLOW_KEY_MARKER.length());
        if (tail.startsWith("{") && tail.endsWith("}")) {
            tail = tail.substring(1, tail.length() - 1);
        }
        try {
            return Long.valueOf(tail);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
