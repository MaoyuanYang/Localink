package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.framework.seckill.SeckillTokenService;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import com.localink.api.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.15 前置令牌语义验证：发放（覆盖式）、一次性消费（GET+DEL 原子）、过期拒绝、
 * 不匹配拒绝且令牌同时作废、无令牌的库存不认识（service 带 token 入口）。
 */
@SpringBootTest
class SeckillTokenIntegrationTest {

    private static final String PHONE = "13900139014";

    @Autowired
    private SeckillTokenService seckillTokenService;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private com.localink.service.VoucherOrderService voucherOrderService;

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
    private com.localink.mapper.VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private SeckillStockCache seckillStockCache;

    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void loginAndSetHolder() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userId = user.getId();
        UserDTO holderUser = new UserDTO();
        holderUser.setId(userId);
        holderUser.setLevel(0);
        UserHolder.set(holderUser);
    }

    @AfterEach
    void cleanup() {
        UserHolder.clear();
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        createdVoucherIds.forEach(id -> {
            voucherOrderMapper.delete(new LambdaQueryWrapper<com.localink.entity.VoucherOrder>()
                    .eq(com.localink.entity.VoucherOrder::getVoucherId, id));
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void issuedTokenConsumesOnceThenRejected() {
        Long voucherId = createVoucher(5);
        String token = seckillTokenService.issue(voucherId, userId);

        seckillTokenService.consume(voucherId, userId, token);

        assertThrows(com.localink.common.exception.LocalinkException.class,
                () -> seckillTokenService.consume(voucherId, userId, token),
                "一次性令牌第二次消费必拒");
    }

    @Test
    void reissueInvalidatesPreviousToken() {
        Long voucherId = createVoucher(5);
        String first = seckillTokenService.issue(voucherId, userId);
        String second = seckillTokenService.issue(voucherId, userId);

        seckillTokenService.consume(voucherId, userId, second);
        com.localink.common.exception.LocalinkException rejected = assertThrows(
                com.localink.common.exception.LocalinkException.class,
                () -> seckillTokenService.consume(voucherId, userId, first),
                "重申后旧令牌应作废");
        assertEquals(BaseCode.SECKILL_TOKEN_INVALID.getCode(), rejected.getCode());
    }

    @Test
    void mismatchedTokenRejectedAndTokenConsumed() {
        Long voucherId = createVoucher(5);
        seckillTokenService.issue(voucherId, userId);

        assertThrows(com.localink.common.exception.LocalinkException.class,
                () -> seckillTokenService.consume(voucherId, userId, "forged-token"),
                "不匹配令牌应拒绝");
        assertThrows(com.localink.common.exception.LocalinkException.class,
                () -> seckillTokenService.consume(voucherId, userId, realTokenOf(voucherId)),
                "被猜错的令牌应同时作废（防重放)");
    }

    @Test
    void seckillWithTokenEntryCreatesOrderAndTokenBurned() throws Exception {
        Long voucherId = createVoucher(5);
        String token = seckillTokenService.issue(voucherId, userId);

        String orderId = voucherOrderService.seckill(voucherId, token);
        assertNotNull(orderId);

        com.localink.entity.VoucherOrder order =
                OrderAwait.awaitById(voucherOrderMapper, Long.valueOf(orderId));
        assertNotNull(order, "令牌入口下单应异步建单");
        assertThrows(com.localink.common.exception.LocalinkException.class,
                () -> voucherOrderService.seckill(voucherId, token),
                "同令牌二次下单：令牌已烧，必拒");
    }

    @Test
    void issueRejectsNonSeckillVoucher() {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("普通券-" + System.nanoTime());
        voucher.setPayValue(100L);
        voucher.setActualValue(10000L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucherMapper.insert(voucher);
        try {
            com.localink.common.exception.LocalinkException rejected = assertThrows(
                    com.localink.common.exception.LocalinkException.class,
                    () -> seckillTokenService.issue(voucher.getId(), userId));
            assertEquals(BaseCode.NOT_FOUND.getCode(), rejected.getCode());
        } finally {
            voucherMapper.deleteById(voucher.getId());
        }
    }

    private String realTokenOf(Long voucherId) {
        return redisCache.strings().getString(keyBuilder.build(KeyManage.SECKILL_TOKEN, voucherId, userId));
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.15令牌券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(stock);
        dto.setMinLevel(0);
        dto.setBeginTime(LocalDateTime.now().minusHours(1).withNano(0));
        dto.setEndTime(LocalDateTime.now().plusHours(2).withNano(0));
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);
        return voucherId;
    }
}
