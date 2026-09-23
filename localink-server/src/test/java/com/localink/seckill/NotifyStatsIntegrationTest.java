package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.delay.DelayQueuePublisher;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.framework.seckill.SeckillTokenService;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mq.DelayTopics;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SubscribeService;
import com.localink.service.SmsService;
import com.localink.service.TopBuyerService;
import com.localink.service.UserService;
import com.localink.service.VoucherOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-C 通知与运营统计验证：预通知（活动级任务+等级圈人+Top 附加+sent 防重+过期跳过）、
 * 订阅（排队/状态/重复订阅首刻保持）、回流自动发券（popMin 最早者补位）、Top 买家（ZINCRBY+降序）。
 * 预通知 lead-minutes=0（测试属性），beginTime=now+4s → 延迟任务约 4s 到期。
 */
@SpringBootTest(properties = {"localink.seckill.notice.lead-minutes=0",
        "localink.order.close-delay=15m"})
class NotifyStatsIntegrationTest {

    private static final String PHONE = "13900139017";
    private static final long HIGH_LEVEL_USER = 9501L;
    private static final long LOW_LEVEL_USER = 9502L;
    private static final long TOP_BUYER_USER = 9503L;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private VoucherOrderService voucherOrderService;

    @Autowired
    private SeckillTokenService seckillTokenService;

    @Autowired
    private SubscribeService subscribeService;

    @Autowired
    private TopBuyerService topBuyerService;

    @Autowired
    private DelayQueuePublisher delayQueuePublisher;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper orderMapper;

    @Autowired
    private com.localink.mapper.VoucherReconcileLogMapper reconcileLogMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private SeckillStockCache seckillStockCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void setUp() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userId = user.getId();
        UserDTO holderUser = new UserDTO();
        holderUser.setId(userId);
        holderUser.setLevel(9);
        UserHolder.set(holderUser);
        insertUser(HIGH_LEVEL_USER, 5);
        insertUser(LOW_LEVEL_USER, 1);
        insertUser(TOP_BUYER_USER, 1);
    }

    @AfterEach
    void cleanup() {
        UserHolder.clear();
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        createdVoucherIds.forEach(id -> {
            orderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            reconcileLogMapper.delete(new LambdaQueryWrapper<com.localink.entity.VoucherReconcileLog>()
                    .eq(com.localink.entity.VoucherReconcileLog::getVoucherId, id));
            seckillStockCache.evict(id);
            redisTemplate.delete(keyBuilder.build(KeyManage.NOTICE_SENT, id).getKey());
            redisTemplate.delete(keyBuilder.build(KeyManage.SUBSCRIBE_QUEUE, id).getKey());
            redisTemplate.delete(keyBuilder.build(KeyManage.SUBSCRIBE_STATUS, id).getKey());
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        for (long uid : List.of(HIGH_LEVEL_USER, LOW_LEVEL_USER, TOP_BUYER_USER)) {
            redisTemplate.delete(keyBuilder.build(KeyManage.USER_NOTICE, uid).getKey());
        }
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        redisTemplate.delete(keyBuilder.build(KeyManage.SHOP_TOP_BUYERS, 77L, today).getKey());
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userMapper.deleteById(HIGH_LEVEL_USER);
        userMapper.deleteById(LOW_LEVEL_USER);
        userMapper.deleteById(TOP_BUYER_USER);
    }

    @Test
    void preNoticeReachesLevelQualifiedUsersAndTopBuyers() throws Exception {
        topBuyerService.recordOrder(77L, TOP_BUYER_USER);
        Long voucherId = createVoucher(5, 3, LocalDateTime.now().plusSeconds(4));

        assertTrue(awaitNotice(HIGH_LEVEL_USER, voucherId, 12_000), "等级达标用户应收到预通知");
        assertTrue(awaitNotice(TOP_BUYER_USER, voucherId, 2_000), "Top 买家应被附加圈选（等级不达标也收）");
        assertFalse(hasNotice(LOW_LEVEL_USER, voucherId), "等级不足用户不得收到");
        assertFalse(hasNotice(userId, voucherId), "登录用户 DB 等级未达标不得收（圈人查 DB 事实源，非会话）");
    }

    @Test
    void preNoticeIsSentOnlyOnceAndSkipsExpiredVoucher() throws Exception {
        Long voucherId = createVoucher(5, 0, LocalDateTime.now().plusSeconds(3));
        assertTrue(awaitNotice(userId, voucherId, 12_000), "首播应送达");

        // 首播已置 sent 标记：手工补投一条同券任务，消费端应被闸门拦下不重发
        Thread.sleep(1_000);
        int before = noticeCount(userId);
        delayQueuePublisher.offerSharded(DelayTopics.SECKILL_NOTICE, voucherId,
                String.valueOf(voucherId), java.time.Duration.ofMillis(200));
        Thread.sleep(2_000);
        assertEquals(before, noticeCount(userId), "sent 闸门应拦截重投不重发");

        // 过期券（beginTime 已过 10min）即使任务到达也不群发
        Long expiredVoucherId = createVoucher(5, 0, LocalDateTime.now().minusMinutes(10));
        delayQueuePublisher.offerSharded(DelayTopics.SECKILL_NOTICE, expiredVoucherId,
                String.valueOf(expiredVoucherId), java.time.Duration.ofMillis(200));
        Thread.sleep(2_000);
        assertFalse(hasNotice(userId, expiredVoucherId), "过期券通知应被开场容差跳过");
    }

    @Test
    void subscribeQueuesAndKeepsFirstPositionOnResubscribe() throws Exception {
        Long voucherId = createVoucher(5, 0, LocalDateTime.now().plusHours(1));
        subscribeService.subscribe(voucherId, HIGH_LEVEL_USER);
        double firstScore = redisCache.zsets().score(
                keyBuilder.build(KeyManage.SUBSCRIBE_QUEUE, voucherId), String.valueOf(HIGH_LEVEL_USER));
        Thread.sleep(20);
        subscribeService.subscribe(voucherId, HIGH_LEVEL_USER);
        assertEquals(firstScore, redisCache.zsets().score(
                        keyBuilder.build(KeyManage.SUBSCRIBE_QUEUE, voucherId), String.valueOf(HIGH_LEVEL_USER)),
                "重复订阅保持首刻排队位置");
        assertEquals("SUBSCRIBED", subscribeService.status(voucherId, HIGH_LEVEL_USER));

        subscribeService.unsubscribe(voucherId, HIGH_LEVEL_USER);
        assertNull(subscribeService.status(voucherId, HIGH_LEVEL_USER), "取消后状态清空");
        assertEquals(0L, redisCache.zsets().size(keyBuilder.build(KeyManage.SUBSCRIBE_QUEUE, voucherId)));
    }

    @Test
    void stockBackflowGrantsEarliestSubscriber() throws Exception {
        Long voucherId = createVoucher(5, 0, LocalDateTime.now().minusHours(1));
        String token = seckillTokenService.issue(voucherId, userId);
        String orderId = voucherOrderService.seckill(voucherId, token);
        assertNotNull(OrderAwait.awaitById(orderMapper, Long.valueOf(orderId)));

        Thread.sleep(50);
        subscribeService.subscribe(voucherId, LOW_LEVEL_USER);
        Thread.sleep(50);
        subscribeService.subscribe(voucherId, TOP_BUYER_USER);

        assertTrue(voucherOrderService.closeOrderIfExpired(Long.valueOf(orderId)), "关单回流");

        VoucherOrder granted = awaitOrderOfUser(voucherId, LOW_LEVEL_USER, 15_000);
        assertNotNull(granted, "最早订阅者应被自动补位建单");
        assertEquals(1, granted.getStatus().intValue());
        assertEquals("GRANTED", subscribeService.status(voucherId, LOW_LEVEL_USER));
        assertEquals(1L, redisCache.zsets().size(keyBuilder.build(KeyManage.SUBSCRIBE_QUEUE, voucherId)),
                "队列应剩一位（后来者继续排队）");
        assertEquals("SUBSCRIBED", subscribeService.status(voucherId, TOP_BUYER_USER));
    }

    @Test
    void topBuyersRankByDailyCount() {
        topBuyerService.recordOrder(77L, HIGH_LEVEL_USER);
        topBuyerService.recordOrder(77L, HIGH_LEVEL_USER);
        topBuyerService.recordOrder(77L, TOP_BUYER_USER);

        List<Map<String, Object>> top = topBuyerService.topBuyers(77L, null, 10);
        assertEquals(HIGH_LEVEL_USER, top.get(0).get("userId"), "两单者应居首");
        assertEquals(2L, top.get(0).get("count"));
        assertEquals(TOP_BUYER_USER, top.get(1).get("userId"));
    }

    private boolean awaitNotice(long targetUserId, Long voucherId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hasNotice(targetUserId, voucherId)) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private boolean hasNotice(long targetUserId, Long voucherId) {
        Set<String> notices = redisCache.zsets().reverseRange(
                keyBuilder.build(KeyManage.USER_NOTICE, targetUserId), 0, -1, String.class);
        return notices.stream().anyMatch(n -> n.contains(String.valueOf(voucherId)));
    }

    private int noticeCount(long targetUserId) {
        return redisCache.zsets().reverseRange(
                keyBuilder.build(KeyManage.USER_NOTICE, targetUserId), 0, -1, String.class).size();
    }

    private VoucherOrder awaitOrderOfUser(Long voucherId, long targetUserId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            VoucherOrder order = orderMapper.selectOne(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .eq(VoucherOrder::getUserId, targetUserId)
                    .last("LIMIT 1"));
            if (order != null) {
                return order;
            }
            Thread.sleep(200);
        }
        return null;
    }

    private void insertUser(long id, int level) {
        User user = new User();
        user.setId(id);
        user.setPhone("138" + String.format("%08d", id));
        user.setLevel(level);
        userMapper.insert(user);
    }

    private Long createVoucher(int stock, int minLevel, LocalDateTime beginTime) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(77L);
        dto.setTitle("M5C通知券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(stock);
        dto.setMinLevel(minLevel);
        dto.setBeginTime(beginTime.withNano(0));
        dto.setEndTime(LocalDateTime.now().plusHours(2));
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);
        return voucherId;
    }
}
