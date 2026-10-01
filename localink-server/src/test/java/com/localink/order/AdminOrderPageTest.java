package com.localink.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.VoucherOrder;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2 admin 订单两端点：按活动查订单（分片库广播归并+券名回填）、手动关单（幂等）。
 * 默认共享上下文。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderPageTest {

    private static final String PHONE_A = "13900139041";
    private static final String PHONE_B = "13900139042";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

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
                voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getUserId, user.getId()));
                userMapper.deleteById(user.getId());
            }
            redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phone));
        }
        createdVoucherIds.forEach(id -> {
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
    }

    @Test
    void adminPageByVoucherReturnsOrdersWithTitles() throws Exception {
        Long voucherId = createSeckillVoucher();
        User userA = ensureUser(PHONE_A);
        User userB = ensureUser(PHONE_B);
        insertOrder(userA.getId(), voucherId);
        insertOrder(userB.getId(), voucherId);

        mockMvc.perform(get("/api/order/admin/page").param("voucherId", String.valueOf(voucherId))
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken(PHONE_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.records[0].voucherId").value(String.valueOf(voucherId)));
    }

    @Test
    void adminPageAnonymousRejected() throws Exception {
        Long voucherId = createSeckillVoucher();
        mockMvc.perform(get("/api/order/admin/page").param("voucherId", String.valueOf(voucherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    void adminCloseClosesOrderThenIdempotent() throws Exception {
        Long voucherId = createSeckillVoucher();
        User userA = ensureUser(PHONE_A);
        VoucherOrder order = insertOrder(userA.getId(), voucherId);

        mockMvc.perform(post("/api/order/admin/{orderId}/close", order.getId())
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken(PHONE_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        VoucherOrder closed = voucherOrderMapper.selectById(order.getId());
        assertEquals(3, closed.getStatus().intValue());
        assertNotNull(closed.getCloseTime());

        mockMvc.perform(post("/api/order/admin/{orderId}/close", order.getId())
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken(PHONE_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    private User ensureUser(String phone) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName("W2订单测试-" + phone);
            user.setLevel(0);
            userMapper.insert(user);
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        }
        return user;
    }

    private Long createSeckillVoucher() {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("W2测试-秒杀券-" + System.nanoTime());
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

    private VoucherOrder insertOrder(Long userId, Long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setVoucherType(2);
        order.setStatus(1);
        order.setReconciliationStatus(1);
        order.setCreateTime(LocalDateTime.now().withNano(0));
        voucherOrderMapper.insert(order);
        return voucherOrderMapper.selectById(order.getId());
    }

    private String loginAndGetToken(String phone) {
        smsService.sendCode(phone);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, phone));
        String token = userService.login(phone, code);
        issuedTokens.add(token);
        return token;
    }
}
