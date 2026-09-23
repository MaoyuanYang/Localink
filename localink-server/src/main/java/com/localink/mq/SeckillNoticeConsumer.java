package com.localink.mq;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.delay.DelayQueueConsumer;
import com.localink.delay.DelayQueuePublisher;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.service.TopBuyerService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 开抢预通知消费者（M5-C）："闹钟挂在活动上，响时查名单"——延迟任务只有一条（活动级），
 * 到期回查事实源再圈名单群发，绝不给每个用户投任务（十万订阅者=到期风暴）。
 *
 * <p>幂等闸门：SETNX 已发标记（延迟任务重投不重发）。过期裁决：now 超过 beginTime+容差则跳过
 * （迟到太久的通知没有意义）。名单 = level 达标用户 + 店铺 Top 买家（附加圈选，去重）。
 * 群发 = 写各用户通知收件箱（ZSet）。</p>
 */
@Slf4j
@Component
public class SeckillNoticeConsumer extends DelayQueueConsumer {

    private static final int TYPE_SECKILL = 2;
    private static final int STATUS_ON_SHELF = 1;
    private static final int MAX_AUDIENCE = 1000;

    private final VoucherMapper voucherMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final UserMapper userMapper;
    private final TopBuyerService topBuyerService;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final StringRedisTemplate redisTemplate;
    private final long expiredToleranceMinutes;

    public SeckillNoticeConsumer(RedissonClient redissonClient,
                                 DelayQueuePublisher delayQueuePublisher,
                                 VoucherMapper voucherMapper,
                                 SeckillVoucherMapper seckillVoucherMapper,
                                 UserMapper userMapper,
                                 TopBuyerService topBuyerService,
                                 RedisCache redisCache,
                                 KeyBuilder keyBuilder,
                                 StringRedisTemplate redisTemplate,
                                 @Value("${localink.seckill.notice.expired-tolerance-minutes:5}") long expiredToleranceMinutes,
                                 @Value("${localink.delay.shards:2}") int shards) {
        super(redissonClient, delayQueuePublisher, DelayTopics.SECKILL_NOTICE, shards);
        this.voucherMapper = voucherMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.userMapper = userMapper;
        this.topBuyerService = topBuyerService;
        this.redisCache = redisCache;
        this.keyBuilder = keyBuilder;
        this.redisTemplate = redisTemplate;
        this.expiredToleranceMinutes = expiredToleranceMinutes;
    }

    @Override
    protected void doConsume(String payload) {
        Long voucherId = Long.valueOf(payload);
        String sentKey = keyBuilder.build(KeyManage.NOTICE_SENT, voucherId).getKey();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(sentKey, "1"))) {
            log.info("预通知已发过, 跳过重投, voucherId={}", voucherId);
            return;
        }
        Voucher voucher = voucherMapper.selectById(voucherId);
        SeckillVoucher seckill = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
        if (voucher == null || seckill == null || voucher.getType() == null || voucher.getType() != TYPE_SECKILL
                || voucher.getStatus() == null || voucher.getStatus() != STATUS_ON_SHELF) {
            log.info("预通知跳过：券已不可抢, voucherId={}", voucherId);
            return;
        }
        LocalDateTime beginTime = seckill.getBeginTime();
        if (beginTime == null || LocalDateTime.now().isAfter(beginTime.plusMinutes(expiredToleranceMinutes))) {
            log.info("预通知跳过：已过开场容差（迟到通知无意义）, voucherId={}, beginTime={}", voucherId, beginTime);
            return;
        }

        Set<Long> audience = selectAudience(voucher.getShopId(), seckill.getMinLevel());
        JSONObject notice = new JSONObject();
        notice.put("type", "PRENOTICE");
        notice.put("voucherId", voucherId);
        notice.put("title", voucher.getTitle());
        notice.put("beginTime", beginTime.toString());
        String noticeJson = notice.toJSONString();
        long now = System.currentTimeMillis();
        for (Long userId : audience) {
            redisCache.zsets().add(keyBuilder.build(KeyManage.USER_NOTICE, userId), noticeJson, now);
        }
        log.info("预通知已群发: voucherId={}, audience={}", voucherId, audience.size());
    }

    /**
     * 名单：达标等级用户（DB 事实源，重放口径）+ 店铺 Top 买家附加（去重并集）。
     */
    private Set<Long> selectAudience(Long shopId, Integer minLevel) {
        Set<Long> audience = new LinkedHashSet<>();
        int threshold = minLevel == null || minLevel <= 0 ? 0 : minLevel;
        userMapper.selectList(new LambdaQueryWrapper<User>()
                        .ge(User::getLevel, threshold)
                        .last("LIMIT " + MAX_AUDIENCE))
                .forEach(user -> audience.add(user.getId()));
        if (shopId != null) {
            topBuyerService.topBuyers(shopId, null, 10)
                    .forEach(row -> audience.add((Long) row.get("userId")));
        }
        return audience;
    }
}
