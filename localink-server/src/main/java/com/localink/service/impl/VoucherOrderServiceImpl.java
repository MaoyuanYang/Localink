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
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
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
            rollbackRedis(voucherId, userId);
            throw e;
        }
        return String.valueOf(orderId);
    }

    /**
     * 消费端建单：orderId 守卫挡重复投递（at-least-once 下的 effectively-once）；
     * CAS 与唯一索引保留为 Redis/DB 短暂不一致时的兜底。
     */
    @Override
    @Transactional
    public void createSeckillOrder(SeckillOrderMessage message) {
        if (voucherOrderMapper.selectById(message.orderId()) != null) {
            log.info("重复投递的建单消息已忽略, orderId={}", message.orderId());
            return;
        }
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
     * 请求路径的即时补偿：Lua 已扣 Redis 但消息发送失败时逆向回加（幂等）。
     * 消费端失败的回滚属 M3.11（重试耗尽才回滚才正确），对账兜底属 M3.12。
     */
    private void rollbackRedis(Long voucherId, Long userId) {
        try {
            redisCache.scripts().execute(seckillRollbackScript,
                    List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId)),
                    String.valueOf(userId));
        } catch (Exception e) {
            log.error("秒杀 Redis 补偿失败, 待对账兜底, voucherId={}, userId={}", voucherId, userId, e);
        }
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
