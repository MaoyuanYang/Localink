package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.entity.VoucherReconcileLog;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.idempotent.RepeatExecuteLimit;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mapper.VoucherReconcileLogMapper;
import com.localink.mapper.RollbackFailureLogMapper;
import com.localink.entity.RollbackFailureLog;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.mq.SeckillOrderMessage;
import com.localink.service.VoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl implements VoucherOrderService {

    private static final int TYPE_SECKILL = 2;
    private static final int STATUS_ON_SHELF = 1;
    private static final int ORDER_STATUS_CREATED = 1;
    private static final int RECONCILIATION_PENDING = 1;

    private static final String DEDUCT_SUCCESS = "0";
    private static final String DEDUCT_NOT_WARMED = "1";
    private static final String DEDUCT_STOCK_EMPTY = "2";
    private static final String DEDUCT_DUPLICATE = "3";
    private static final String ROLLBACK_DONE_PREFIX = "0";

    private static final int LOG_TYPE_DEDUCT = 1;
    private static final int LOG_TYPE_RESTORE = 2;
    private static final int BUSINESS_ORDER_OK = 1;
    private static final int BUSINESS_ORDER_TIMEOUT = 2;
    private static final int BUSINESS_ORDER_FAIL = 3;

    private final VoucherMapper voucherMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final VoucherOrderMapper voucherOrderMapper;
    private final VoucherReconcileLogMapper voucherReconcileLogMapper;
    private final RollbackFailureLogMapper rollbackFailureLogMapper;
    private final com.localink.mapper.OrderRouteMapper orderRouteMapper;
    private final RedisCache redisCache;
    private final SeckillStockCache seckillStockCache;
    private final com.localink.framework.seckill.SeckillTokenService seckillTokenService;
    private final com.localink.id.SnowflakeIdGenerator snowflakeIdGenerator;
    private final com.localink.delay.DelayQueuePublisher delayQueuePublisher;
    private final com.localink.service.SubscribeService subscribeService;
    private final com.localink.service.TopBuyerService topBuyerService;
    private final RedisScript<String> seckillDeductScript;
    private final RedisScript<String> seckillRollbackScript;
    private final MessageProducer messageProducer;

    @org.springframework.beans.factory.annotation.Value("${localink.order.close-delay:15m}")
    private java.time.Duration orderCloseDelay;

    @Override
    public String seckill(Long voucherId, String token) {
        seckillTokenService.consume(voucherId, UserHolder.get().getId(), token);
        return seckill(voucherId);
    }

    @Override
    public String seckill(Long voucherId) {
        Voucher voucher = requireSeckillVoucher(voucherId);
        SeckillVoucher seckill = requireOpenSeckill(voucherId);
        requireUserLevel(seckill.getMinLevel());
        Long userId = UserHolder.get().getId();

        long orderId = snowflakeIdGenerator.nextId();
        long traceId = snowflakeIdGenerator.nextId();
        DeductAccount account = deductInRedis(voucherId, userId, traceId, seckill.getEndTime());

        SeckillOrderMessage message = new SeckillOrderMessage(orderId, voucherId, voucher.getType(),
                userId, traceId, account.before(), account.after());
        try {
            messageProducer.sendSync(MqTopics.SECKILL_ORDER, String.valueOf(voucherId), message);
        } catch (RuntimeException e) {
            rollbackSeckillQualification(voucherId, userId, orderId, traceId, "REQUEST_SEND",
                    "seckill send failed: " + e.getMessage());
            throw e;
        }
        return String.valueOf(orderId);
    }

    /**
     * 消费端建单：@RepeatExecuteLimit 以 orderId 为幂等键挡重复投递（标记快路径 → 唯一索引终审；
     * 标记写在事务提交后，回滚的执行不落标记、重投会重试）。CAS 与唯一索引保留为标记丢失时的兜底。
     * 订单与扣减流水行同事务写入——DB 流水与订单同生共死，对账永无"有流水无订单"的中间态噪声。
     */
    @Override
    @RepeatExecuteLimit(name = "seckill-order", key = "#message.orderId()")
    @Transactional
    public void createSeckillOrder(SeckillOrderMessage message, String messageId) {
        int deducted = seckillVoucherMapper.deductStock(message.voucherId());
        if (deducted == 0) {
            throw new LocalinkException(BaseCode.SECKILL_STOCK_NOT_ENOUGH,
                    "Redis 已扣减但 DB 库存不足, orderId=" + message.orderId());
        }
        VoucherOrder order = new VoucherOrder();
        order.setId(message.orderId());
        order.setUserId(message.userId());
        order.setVoucherId(message.voucherId());
        order.setVoucherType(message.voucherType());
        order.setStatus(ORDER_STATUS_CREATED);
        order.setReconciliationStatus(RECONCILIATION_PENDING);
        try {
            voucherOrderMapper.insert(order);
            voucherReconcileLogMapper.insert(buildDeductLog(message, messageId));
            orderRouteMapper.insert(buildRoute(message));
        } catch (DuplicateKeyException e) {
            log.info("唯一索引拦截重复建单（守卫与插入间隙的竞态）, orderId={}", message.orderId());
            return;
        }
        // M5-C：店铺每日 Top 买家记账（按日 ZSet，ZINCRBY 幂等口径=单数自然累计）
        Voucher voucher = voucherMapper.selectById(message.voucherId());
        if (voucher != null && voucher.getShopId() != null) {
            topBuyerService.recordOrder(voucher.getShopId(), message.userId());
        }
        // M5-B：超时关单延迟任务（orderId 分片路由）。事务内投递——若事务回滚则任务空转，
        // 消费端条件关单（status=1 才关）天然幂等，空转无副作用；投递失败仅日志不阻断建单
        delayQueuePublisher.offerSharded(com.localink.mq.DelayTopics.ORDER_CLOSE,
                message.orderId(), String.valueOf(message.orderId()), orderCloseDelay);
    }

    /**
     * Lua 原子完成"库存判定 + 一人一单判重 + 扣减 + 流水落账"——单线程执行无并发缝隙。
     * 成功返回携账目（traceId/前后库存）供建消息携带；失败仍为单数字码。
     */
    private DeductAccount deductInRedis(Long voucherId, Long userId, long traceId, LocalDateTime endTime) {
        String ttlSeconds = String.valueOf(Math.max(1,
                Duration.between(LocalDateTime.now(),
                        endTime == null ? LocalDateTime.now().plusHours(24) : endTime).toSeconds()));
        String result = redisCache.scripts().execute(seckillDeductScript,
                List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId),
                        seckillStockCache.flowKey(voucherId)),
                String.valueOf(userId), ttlSeconds, String.valueOf(traceId),
                String.valueOf(System.currentTimeMillis()));
        if (result == null) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "秒杀脚本无返回值");
        }
        String[] parts = result.split("\\|");
        if (DEDUCT_SUCCESS.equals(parts[0])) {
            return new DeductAccount(Long.parseLong(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        }
        if (DEDUCT_STOCK_EMPTY.equals(result)) {
            throw new LocalinkException(BaseCode.SECKILL_STOCK_NOT_ENOUGH);
        }
        if (DEDUCT_DUPLICATE.equals(result)) {
            throw new LocalinkException(BaseCode.SECKILL_DUPLICATE_ORDER);
        }
        throw new LocalinkException(BaseCode.SYSTEM_ERROR, "库存未预热，等待回灌后重试");
    }

    /**
     * 统一回滚入口（M3.11 建立，M3.12 增流水账）：请求发送失败（立即）/ 消费重试耗尽（recoverer）/
     * 超龄丢弃（beforeConsume）三处共用。逆增量 Lua 幂等可重试并翻 Redis 流水为恢复态；
     * 成功后落 DB 恢复流水行（business_type 按 source 归类）。Lua 或落行失败落 lk_rollback_failure_log
     * 供 M5.2 补偿告警——重试触发时 Lua 返回"无需补偿"即安全收敛。
     */
    @Override
    public void rollbackSeckillQualification(Long voucherId, Long userId, Long orderId, Long traceId,
                                              String source, String detail) {
        try {
            String result = redisCache.scripts().execute(seckillRollbackScript,
                    List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId),
                            seckillStockCache.flowKey(voucherId)),
                    String.valueOf(userId), traceId == null ? "" : String.valueOf(traceId),
                    String.valueOf(System.currentTimeMillis()));
            if (result == null) {
                throw new IllegalStateException("回滚脚本无返回值");
            }
            if (!result.startsWith(ROLLBACK_DONE_PREFIX)) {
                log.info("回滚无需补偿（用户不在已购集合）, voucherId={}, userId={}, source={}",
                        voucherId, userId, source);
                return;
            }
            String[] parts = result.split("\\|");
            voucherReconcileLogMapper.insert(buildRestoreLog(voucherId, userId, orderId, traceId,
                    source, detail, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (Exception e) {
            log.error("秒杀 Redis 回滚失败, 已落失败表待补偿, voucherId={}, userId={}, source={}",
                    voucherId, userId, source, e);
            RollbackFailureLog failureLog = new RollbackFailureLog();
            failureLog.setVoucherId(voucherId);
            failureLog.setUserId(userId);
            failureLog.setOrderId(orderId);
            failureLog.setTraceId(traceId);
            failureLog.setRetryAttempts(0);
            failureLog.setSource(source);
            failureLog.setDetail(detail + " | rollback error: " + e.getMessage());
            rollbackFailureLogMapper.insert(failureLog);
        }
    }

    @Override
    public boolean seckillOrderExists(Long orderId) {
        return voucherOrderMapper.selectById(orderId) != null;
    }

    /**
     * M5-B 超时关单：条件关单（affected=0 即已处置，直接跳过——重投/多投/空转任务的幂等闸门）
     * → DB 库存逆增量回补 → 复用统一回滚退 Redis 资格（库存+1/出集合/流水翻恢复+恢复行）。
     * Redis 回滚失败由统一入口落失败表（M5-A 对账重试收敛）——关单链路的兜底闭环复用自既有体系。
     */
    @Override
    public boolean closeOrderIfExpired(Long orderId) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }
        int closed = voucherOrderMapper.closeIfCreated(orderId);
        if (closed == 0) {
            log.info("关单跳过（订单已非创建态或已处置）, orderId={}, status={}", orderId, order.getStatus());
            return false;
        }
        seckillVoucherMapper.restoreStock(order.getVoucherId());
        Long traceId = resolveTraceId(orderId);
        rollbackSeckillQualification(order.getVoucherId(), order.getUserId(), orderId, traceId,
                "ORDER_CLOSE", "order expired and closed by delay task");
        subscribeService.tryGrantEarliest(order.getVoucherId());
        return true;
    }

    private Long resolveTraceId(Long orderId) {
        VoucherReconcileLog deductRow = voucherReconcileLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getOrderId, orderId)
                        .eq(VoucherReconcileLog::getLogType, 1)
                        .last("LIMIT 1"));
        return deductRow == null ? orderId : deductRow.getTraceId();
    }

    /**
     * M4.5 反查：路由表取分片键 → 计算物理位置（库 user_id%2、表 voucher_id%2）→ 带键精确查询。
     * 路由行缺失（跨库写缝隙）返回 null 位置信息并以广播兜底查存在性。
     */
    @Override
    public OrderLocation locateOrder(Long orderId) {
        com.localink.entity.OrderRoute route = orderRouteMapper.selectOne(
                new LambdaQueryWrapper<com.localink.entity.OrderRoute>()
                        .eq(com.localink.entity.OrderRoute::getOrderId, orderId).last("LIMIT 1"));
        if (route == null) {
            return new OrderLocation(orderId, null, null, null, null, seckillOrderExists(orderId));
        }
        String dataSource = "ds_" + (Math.floorMod(route.getUserId(), 2));
        String physicalTable = "lk_voucher_order_" + (Math.floorMod(route.getVoucherId(), 2));
        return new OrderLocation(orderId, route.getUserId(), route.getVoucherId(),
                dataSource, physicalTable, seckillOrderExists(orderId));
    }

    private com.localink.entity.OrderRoute buildRoute(SeckillOrderMessage message) {
        com.localink.entity.OrderRoute route = new com.localink.entity.OrderRoute();
        route.setOrderId(message.orderId());
        route.setUserId(message.userId());
        route.setVoucherId(message.voucherId());
        return route;
    }

    /**
     * 扣减流水行（logType=1）：账目数字来自消息携带的 Lua 返回；旧消息缺 traceId 时以 orderId 顶替
     * （同一资格生命周期，语义不变）。行随建单事务写入，uk_order_log(order_id, log_type) 拦重投双写。
     */
    private VoucherReconcileLog buildDeductLog(SeckillOrderMessage message, String messageId) {
        VoucherReconcileLog logRow = new VoucherReconcileLog();
        logRow.setOrderId(message.orderId());
        logRow.setUserId(message.userId());
        logRow.setVoucherId(message.voucherId());
        logRow.setTraceId(Objects.requireNonNullElse(message.traceId(), message.orderId()));
        logRow.setMessageId(messageId);
        logRow.setLogType(LOG_TYPE_DEDUCT);
        logRow.setBusinessType(BUSINESS_ORDER_OK);
        logRow.setBeforeQty(message.stockBefore());
        logRow.setChangeQty(-1);
        logRow.setAfterQty(message.stockAfter());
        logRow.setReconciliationStatus(RECONCILIATION_PENDING);
        return logRow;
    }

    /**
     * 恢复流水行（logType=2）：回滚成功后落；STALE_DROP 归"下单超时"，其余归"下单失败"。
     * traceId 缺失（旧消息/手工消息）时以 orderId 顶替、orderId 缺失（消息全丢的对账补偿/失败表重试）
     * 时以 traceId 顶替——两者皆雪花数值，列 NOT NULL 约束下的合法占位。
     */
    private VoucherReconcileLog buildRestoreLog(Long voucherId, Long userId, Long orderId, Long traceId,
                                                String source, String detail, int before, int after) {
        VoucherReconcileLog logRow = new VoucherReconcileLog();
        logRow.setOrderId(Objects.requireNonNullElse(orderId, traceId));
        logRow.setUserId(userId);
        logRow.setVoucherId(voucherId);
        logRow.setTraceId(Objects.requireNonNullElse(traceId, orderId));
        logRow.setLogType(LOG_TYPE_RESTORE);
        logRow.setBusinessType("STALE_DROP".equals(source) || "ORDER_CLOSE".equals(source)
                ? BUSINESS_ORDER_TIMEOUT : BUSINESS_ORDER_FAIL);
        logRow.setBeforeQty(before);
        logRow.setChangeQty(1);
        logRow.setAfterQty(after);
        logRow.setReconciliationStatus(RECONCILIATION_PENDING);
        logRow.setDetail("source=" + source + " | " + detail);
        return logRow;
    }

    private Voucher requireSeckillVoucher(Long voucherId) {
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "秒杀券不存在");
        }
        if (voucher.getType() == null || voucher.getType() != TYPE_SECKILL) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "目标券不是秒杀券");
        }
        if (voucher.getStatus() == null || voucher.getStatus() != STATUS_ON_SHELF) {
            throw new LocalinkException(BaseCode.VOUCHER_NOT_AVAILABLE);
        }
        return voucher;
    }

    private SeckillVoucher requireOpenSeckill(Long voucherId) {
        SeckillVoucher seckill = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
        if (seckill == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "秒杀券库存信息不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (seckill.getBeginTime() != null && now.isBefore(seckill.getBeginTime())) {
            throw new LocalinkException(BaseCode.SECKILL_NOT_STARTED);
        }
        if (seckill.getEndTime() != null && now.isAfter(seckill.getEndTime())) {
            throw new LocalinkException(BaseCode.SECKILL_ENDED);
        }
        return seckill;
    }

    private void requireUserLevel(Integer minLevel) {
        if (minLevel == null || minLevel <= 0) {
            return;
        }
        Integer level = UserHolder.get().getLevel();
        if (level == null || level < minLevel) {
            throw new LocalinkException(BaseCode.SECKILL_LEVEL_NOT_ENOUGH);
        }
    }

    /**
     * 扣减成功账目：traceId + 扣减前后库存（Lua 返回值解析产物，随消息流转到消费端落 DB 流水）。
     */
    private record DeductAccount(long traceId, int before, int after) {
    }
}
