package com.localink.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.framework.holder.UserHolder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W1 我的订单分页：GET 匿名可达但 Service 收口 40002；分页/倒序/券名回填/size 钳制。
 * 默认共享上下文（不新开 properties 上下文——W0 排障 1 的 MySQL 151 峰值教训）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPageIntegrationTest {

    private static final String PHONE = "13900139031";

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
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        if (user != null) {
            voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getUserId, user.getId()));
            userMapper.deleteById(user.getId());
        }
        createdVoucherIds.forEach(id -> {
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        mockMvc.perform(get("/api/order/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    void pageMyOrdersReturnsDescOrdersWithTitles() throws Exception {
        User user = ensureUser();
        Long closedNormalVoucherId = createNormalVoucher();
        Long seckillVoucherId = createSeckillVoucher();
        Long activeNormalVoucherId = createNormalVoucher();

        insertOrder(user.getId(), closedNormalVoucherId, 1, 3, LocalDateTime.now().minusMinutes(5));
        insertOrder(user.getId(), seckillVoucherId, 2, 1, null);
        insertOrder(user.getId(), activeNormalVoucherId, 1, 1, null);

        mockMvc.perform(get("/api/order/page").param("page", "1").param("size", "10")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(3))
                .andExpect(jsonPath("$.data.records[0].voucherId").value(String.valueOf(activeNormalVoucherId)))
                .andExpect(jsonPath("$.data.records[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.records[0].status").value(1))
                .andExpect(jsonPath("$.data.records[1].voucherId").value(String.valueOf(seckillVoucherId)))
                .andExpect(jsonPath("$.data.records[1].title").isNotEmpty())
                .andExpect(jsonPath("$.data.records[1].voucherType").value(2))
                .andExpect(jsonPath("$.data.records[2].voucherId").value(String.valueOf(closedNormalVoucherId)))
                .andExpect(jsonPath("$.data.records[2].status").value(3))
                .andExpect(jsonPath("$.data.records[2].closeTime").isNotEmpty());
    }

    @Test
    void sizeClampedToUpperBound() throws Exception {
        ensureUser();
        mockMvc.perform(get("/api/order/page").param("size", "200")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.size").value(50));
    }

    private User ensureUser() {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        if (user == null) {
            user = new User();
            user.setPhone(PHONE);
            user.setNickName("W1订单测试用户");
            user.setLevel(0);
            userMapper.insert(user);
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        }
        return user;
    }

    private Long createNormalVoucher() {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("W1测试-普通券-" + System.nanoTime());
        voucher.setPayValue(0L);
        voucher.setActualValue(1000L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucherMapper.insert(voucher);
        createdVoucherIds.add(voucher.getId());
        return voucher.getId();
    }

    private Long createSeckillVoucher() {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("W1测试-秒杀券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(5);
        dto.setMinLevel(0);
        dto.setBeginTime(LocalDateTime.now().minusHours(1).withNano(0));
        dto.setEndTime(LocalDateTime.now().plusHours(1).withNano(0));
        Long id = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(id);
        return id;
    }

    private void insertOrder(Long userId, Long voucherId, int voucherType, int status, LocalDateTime closeTime) {
        VoucherOrder order = new VoucherOrder();
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setVoucherType(voucherType);
        order.setStatus(status);
        order.setReconciliationStatus(1);
        order.setCreateTime(LocalDateTime.now().withNano(0));
        order.setCloseTime(closeTime);
        voucherOrderMapper.insert(order);
    }

    private String loginAndGetToken() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        return token;
    }
}
