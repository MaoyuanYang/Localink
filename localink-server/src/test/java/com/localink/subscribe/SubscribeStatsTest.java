package com.localink.subscribe;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
import com.localink.service.SubscribeService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2 订阅运营统计：ZCARD 队列规模、Hash 状态分布、notice:sent 标记、匿名拒绝。
 * 默认共享上下文。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubscribeStatsTest {

    private static final String PHONE_A = "13900139051";
    private static final String PHONE_B = "13900139052";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private SubscribeService subscribeService;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private SeckillStockCache seckillStockCache;

    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String phone : List.of(PHONE_A, PHONE_B)) {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
            if (user != null) {
                userMapper.deleteById(user.getId());
            }
            redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phone));
        }
        createdVoucherIds.forEach(id -> {
            redisCache.delete(keyBuilder.build(KeyManage.SUBSCRIBE_QUEUE, id));
            redisCache.delete(keyBuilder.build(KeyManage.SUBSCRIBE_STATUS, id));
            redisCache.delete(keyBuilder.build(KeyManage.NOTICE_SENT, String.valueOf(id)));
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
    }

    @Test
    void statsCountsQueueStatusAndNoticeFlag() throws Exception {
        Long voucherId = createSeckillVoucher();
        User userA = ensureUser(PHONE_A);
        User userB = ensureUser(PHONE_B);
        subscribeService.subscribe(voucherId, userA.getId());
        subscribeService.subscribe(voucherId, userB.getId());
        redisCache.hashes().put(keyBuilder.build(KeyManage.SUBSCRIBE_STATUS, voucherId),
                String.valueOf(userB.getId()), "GRANTED");
        redisCache.strings().setIfAbsent(keyBuilder.build(KeyManage.NOTICE_SENT, String.valueOf(voucherId)),
                "1", KeyManage.NOTICE_SENT.getTtl());

        mockMvc.perform(get("/api/seckill-voucher/{voucherId}/subscribe-stats", voucherId)
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken(PHONE_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.queueSize").value(2))
                .andExpect(jsonPath("$.data.subscribedCount").value(1))
                .andExpect(jsonPath("$.data.grantedCount").value(1))
                .andExpect(jsonPath("$.data.noticeSent").value(true))
                .andExpect(jsonPath("$.data.title").isNotEmpty())
                .andExpect(jsonPath("$.data.beginTime").isNotEmpty());
    }

    @Test
    void statsAnonymousRejected() throws Exception {
        Long voucherId = createSeckillVoucher();
        mockMvc.perform(get("/api/seckill-voucher/{voucherId}/subscribe-stats", voucherId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
    }

    private User ensureUser(String phone) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName("W2订阅测试-" + phone);
            user.setLevel(0);
            userMapper.insert(user);
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        }
        return user;
    }

    private Long createSeckillVoucher() {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("W2订阅测试-秒杀券-" + System.nanoTime());
        dto.setPayValue(1000L);
        dto.setActualValue(10000L);
        dto.setStock(5);
        dto.setMinLevel(0);
        dto.setBeginTime(LocalDateTime.now().minusHours(1).withNano(0));
        dto.setEndTime(LocalDateTime.now().plusHours(1).withNano(0));
        Long id = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(id);
        return id;
    }

    private String loginAndGetToken(String phone) {
        smsService.sendCode(phone);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, phone));
        String token = userService.login(phone, code);
        issuedTokens.add(token);
        return token;
    }
}
