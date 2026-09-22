package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.idempotent.RepeatExecuteLimit;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl implements VoucherOrderService {

    private static final int TYPE_SECKILL = 2;
    private static final int STATUS_ON_SHELF = 1;
    private static final int ORDER_STATUS_CREATED = 1;
    private static final int RECONCILIATION_PENDING = 1;

    private static final long DEDUCT_SUCCESS = 0;
    private static final long DEDUCT_NOT_WARMED = 1;
    private static final long DEDUCT_STOCK_EMPTY = 2;
    private static final long DEDUCT_DUPLICATE = 3;

    private final VoucherMapper voucherMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final VoucherOrderMapper voucherOrderMapper;
    private final RollbackFailureLogMapper rollbackFailureLogMapper;
    private final RedisCache redisCache;
    private final SeckillStockCache seckillStockCache;
    private final RedisScript<Long> seckillDeductScript;
    private final RedisScript<Long> seckillRollbackScript;
    private final MessageProducer messageProducer;

    @Override
    public String seckill(Long voucherId) {
        Voucher voucher = requireSeckillVoucher(voucherId);
        SeckillVoucher seckill = requireOpenSeckill(voucherId);
        requireUserLevel(seckill.getMinLevel());
        Long userId = UserHolder.get().getId();

        deductInRedis(voucherId, userId, seckill.getEndTime());

        long orderId = IdWorker.getId();
        SeckillOrderMessage message =
                new SeckillOrderMessage(orderId, voucherId, voucher.getType(), userId);
        try {
            messageProducer.sendSync(MqTopics.SECKILL_ORDER, String.valueOf(voucherId), message);
        } catch (RuntimeException e) {
            rollbackSeckillQualification(voucherId, userId, "REQUEST_SEND",
                    "seckill send failed: " + e.getMessage());
            throw e;
        }
        return String.valueOf(orderId);
    }

    /**
     * 消费端建单：@RepeatExecuteLimit 以 orderId 为幂等键挡重复投递（标记快路径 → 唯一索引终审；
     * 标记写在事务提交后，回滚的执行不落标记、重投会重试）。CAS 与唯一索引保留为标记丢失时的兜底。
     */
    @Override
    @RepeatExecuteLimit(name = "seckill-order", key = "#message.orderId()")
    @Transactional
    public void createSeckillOrder(SeckillOrderMessage message) {
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
        } catch (DuplicateKeyException e) {
            log.info("唯一索引拦截重复建单（守卫与插入间隙的竞态）, orderId={}", message.orderId());
        }
    }

    /**
     * Lua 原子完成"库存判定 + 一人一单判重 + 扣减"——单线程执行无并发缝隙。
     */
    private void deductInRedis(Long voucherId, Long userId, LocalDateTime endTime) {
        String ttlSeconds = String.valueOf(Math.max(1,
                Duration.between(LocalDateTime.now(),
                        endTime == null ? LocalDateTime.now().plusHours(24) : endTime).toSeconds()));
        Long result = redisCache.scripts().execute(seckillDeductScript,
                List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId)),
                String.valueOf(userId), ttlSeconds);
        if (result == null) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "秒杀脚本无返回值");
        }
        if (result == DEDUCT_SUCCESS) {
            return;
        }
        if (result == DEDUCT_STOCK_EMPTY) {
            throw new LocalinkException(BaseCode.SECKILL_STOCK_NOT_ENOUGH);
        }
        if (result == DEDUCT_DUPLICATE) {
            throw new LocalinkException(BaseCode.SECKILL_DUPLICATE_ORDER);
        }
        throw new LocalinkException(BaseCode.SYSTEM_ERROR, "库存未预热，等待回灌后重试");
    }

    /**
     * 统一回滚入口（M3.11）：请求发送失败（立即）/ 消费重试耗尽（recoverer）/ 超龄丢弃（beforeConsume）
     * 三处共用。逆增量 Lua 幂等可重试；执行失败落 lk_rollback_failure_log 供 M5.2 补偿告警。
     */
    @Override
    public void rollbackSeckillQualification(Long voucherId, Long userId, String source, String detail) {
        try {
            redisCache.scripts().execute(seckillRollbackScript,
                    List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId)),
                    String.valueOf(userId));
        } catch (Exception e) {
            log.error("秒杀 Redis 回滚失败, 已落失败表待补偿, voucherId={}, userId={}, source={}",
                    voucherId, userId, source, e);
            RollbackFailureLog failureLog = new RollbackFailureLog();
            failureLog.setVoucherId(voucherId);
            failureLog.setUserId(userId);
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
}
