package com.localink.framework.seckill;

import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Voucher;
import com.localink.mapper.VoucherMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 秒杀前置令牌（M3.15）：申请端点经 @RateLimit 双维度闸门后发放（覆盖式：重申刷新、旧令牌作废，
 * 单用户同时至多一枚有效令牌）；下单链路 Lua 原子 GET+DEL 一次性消费——令牌是进库存扣减的门票，
 * 库存与 Lua 判重仍是正确性最终防线（令牌挡流量，不挡超卖）。
 */
@Component
@RequiredArgsConstructor
public class SeckillTokenService {

    private static final int TYPE_SECKILL = 2;
    private static final int STATUS_ON_SHELF = 1;
    private static final long TOKEN_TTL_SECONDS = 30;

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final VoucherMapper voucherMapper;
    private final RedisScript<Long> consumeSeckillTokenScript;

    /**
     * 申请令牌：轻校验（券存在、是秒杀券、在架——防无效券刷令牌；时间窗不卡，
     * 开抢前囤的票 30s TTL 自然过期），覆盖式发放。
     */
    public String issue(Long voucherId, Long userId) {
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null || voucher.getType() == null || voucher.getType() != TYPE_SECKILL
                || voucher.getStatus() == null || voucher.getStatus() != STATUS_ON_SHELF) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "秒杀券不存在或不可抢");
        }
        String token = UUID.randomUUID().toString();
        redisCache.strings().set(keyBuilder.build(KeyManage.SECKILL_TOKEN, voucherId, userId), token);
        redisCache.expire(keyBuilder.build(KeyManage.SECKILL_TOKEN, voucherId, userId),
                Duration.ofSeconds(TOKEN_TTL_SECONDS));
        return token;
    }

    /**
     * 一次性消费：Lua 原子 GET+DEL。未申请/过期/已消费/不匹配均拒绝
     * （不匹配也删——防猜测重放；消费通过即删，同令牌第二次必拒）。
     */
    public void consume(Long voucherId, Long userId, String token) {
        Long result = redisCache.scripts().execute(consumeSeckillTokenScript,
                List.of(keyBuilder.build(KeyManage.SECKILL_TOKEN, voucherId, userId)), token);
        if (result == null || result != 0) {
            throw new LocalinkException(BaseCode.SECKILL_TOKEN_INVALID);
        }
    }
}
