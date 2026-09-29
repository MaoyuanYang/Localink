package com.localink.framework.reconcile;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.common.metrics.MetricsPort;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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
    private final Duration orderCloseDelay;
    private final org.springframework.beans.factory.ObjectProvider<MetricsPort> metricsPort;

    public ReconciliationJob(StringRedisTemplate redisTemplate,
                             KeyBuilder keyBuilder,
                             VoucherReconcileLogMapper reconcileLogMapper,
                             VoucherOrderMapper orderMapper,
                             RollbackFailureLogMapper rollbackFailureLogMapper,
                             VoucherOrderService voucherOrderService,
                             ReconcileProperties properties,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${localink.order.close-delay:15m}") Duration orderCloseDelay,
                             org.springframework.beans.factory.ObjectProvider<MetricsPort> metricsPort) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
        this.reconcileLogMapper = reconcileLogMapper;
        this.orderMapper = orderMapper;
        this.rollbackFailureLogMapper = rollbackFailureLogMapper;
        this.voucherOrderService = voucherOrderService;
        this.properties = properties;
        this.orderCloseDelay = orderCloseDelay;
        this.metricsPort = metricsPort;
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
                    // 逐笔隔离：单条损坏流水（JSON 解析失败/字段漂移）只跳过自身，
                    // 不再冒泡中止整轮对账（含失败表重试），否则毒流水会让对账永久卡死在同一位置
                    try {
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
                            compensated += settleAccordingToOrderState(deductRow, voucherId, userId, traceId,
                                    flow.getLongValue("ts"));
                        }
                    } catch (Exception e) {
                        log.error("对账单笔处理异常, 已跳过该流水, key={}, field={}",
                                key, entry.getKey(), e);
                    }
                }
            }
        }
        compensated += closeOverdueCreatedOrders();
        retryRollbackFailures();
        log.info("对账完成: 差异补偿={}笔, 状态翻牌={}笔", compensated, flipped);
        return compensated;
    }

    /**
     * 挂单补裁（A-3）：延迟任务"take 即离队、崩溃即丢、重试耗尽仅告警"，丢失后 status=1 订单
     * 原会被 settleAccordingToOrderState 翻牌为"一致"且无任何回收路径。这里兜底扫一遍
     * 超过 关单延迟+宽限期 仍处创建态的订单，走条件关单幂等闸门补关——与延迟任务并发安全。
     */
    private int closeOverdueCreatedOrders() {
        LocalDateTime deadline = LocalDateTime.now()
                .minus(orderCloseDelay)
                .minusMinutes(properties.getGraceMinutes());
        List<VoucherOrder> overdue = orderMapper.selectList(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getStatus, 1)
                .lt(VoucherOrder::getCreateTime, deadline)
                .last("LIMIT 50"));
        for (VoucherOrder order : overdue) {
            log.warn("延迟关单任务疑似丢失, 对账补关单, orderId={}, createTime={}",
                    order.getId(), order.getCreateTime());
            voucherOrderService.closeOrderIfExpired(order.getId());
            metricsPort.getIfAvailable(() -> MetricsPort.NOOP)
                    .increment("localink.order.close", "source", "reconcile_sweep");
        }
        return overdue.size();
    }

    /**
     * 按订单终态裁决（M5-B 联动补强）：已取消/已关闭订单的扣减资格必须已回滚（流水应翻 logType=2）——
     * 终态订单配 logType=1 流水即"回流缺失"差异，补回滚（幂等：首次回滚成功后 Lua 返回无需补偿）。
     * 活跃订单走一致翻牌。关单三步（条件关单/DB 回补/Redis 回滚）非原子，关单与回滚间的窗口
     * 由本裁决 + rollback 幂等收敛——对账是关单链路的最终兜底层。
     */
    private int settleAccordingToOrderState(VoucherReconcileLog deductRow, Long voucherId, Long userId,
                                            Long traceId, long flowTs) {
        VoucherOrder order = orderMapper.selectById(deductRow.getOrderId());
        if (order != null && (order.getStatus() == 2 || order.getStatus() == 3)) {
            if (withinGrace(flowTs)) {
                return 0;
            }
            log.error("对账差异[告警]: 订单已终态但 Redis 流水未恢复, 补偿回滚, orderId={}, status={}, traceId={}",
                    order.getId(), order.getStatus(), traceId);
            voucherOrderService.rollbackSeckillQualification(voucherId, userId, order.getId(), traceId,
                    "RECONCILE_RESTORE_MISS", "order terminal but redis flow not restored");
            return 1;
        }
        return settleConsistentTrace(deductRow);
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
        metricsPort.getIfAvailable(() -> MetricsPort.NOOP).increment("localink.reconcile.compensated");
    }

    /**
     * 回滚失败表重试：成功（回滚完成或判定无需补偿）才删行；失败保留原行并累加 retry_attempts——
     * 行龄（create_time）随失败持续增长，超过阈值仍存在 = 长期不收敛，error 告警人工介入。
     * 原"删旧走新"会使失败行每轮重置 create_time，行龄告警结构性不可达，已废弃。
     */
    private void retryRollbackFailures() {
        LocalDateTime alarmBefore = LocalDateTime.now().minusHours(properties.getFailureAgeAlarmHours());
        for (RollbackFailureLog failure : rollbackFailureLogMapper.selectList(null)) {
            if (failure.getCreateTime() != null && failure.getCreateTime().isBefore(alarmBefore)) {
                log.error("回滚失败行长期未收敛[告警]: id={}, voucherId={}, userId={}, attempts={}, created={}",
                        failure.getId(), failure.getVoucherId(), failure.getUserId(),
                        failure.getRetryAttempts(), failure.getCreateTime());
            }
            boolean converged = voucherOrderService.rollbackSeckillQualification(failure.getVoucherId(),
                    failure.getUserId(), failure.getOrderId(), failure.getTraceId(),
                    "RETRY", "reconcile retry of source=" + failure.getSource());
            if (converged) {
                rollbackFailureLogMapper.deleteById(failure.getId());
            } else {
                // 统一入口失败路径会另插新行——删除同 (voucher,user) 的更新重复行，
                // 保留本行（原始 create_time 不重置，行龄告警才可达）
                rollbackFailureLogMapper.delete(new LambdaQueryWrapper<RollbackFailureLog>()
                        .eq(RollbackFailureLog::getVoucherId, failure.getVoucherId())
                        .eq(RollbackFailureLog::getUserId, failure.getUserId())
                        .gt(RollbackFailureLog::getId, failure.getId()));
                rollbackFailureLogMapper.update(null, new LambdaUpdateWrapper<RollbackFailureLog>()
                        .eq(RollbackFailureLog::getId, failure.getId())
                        .set(RollbackFailureLog::getRetryAttempts,
                                (failure.getRetryAttempts() == null ? 0 : failure.getRetryAttempts()) + 1));
                log.error("回滚失败表重试未收敛, 行保留待下轮, id={}, voucherId={}, userId={}",
                        failure.getId(), failure.getVoucherId(), failure.getUserId());
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
