package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import com.localink.service.VoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class SeckillClaimBoundaryTest {

    private static final String PHONE = "13900139007";

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

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

    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();

    @BeforeEach
    void loginAndSetHolder() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        UserDTO holderUser = new UserDTO();
        holderUser.setId(user.getId());
        holderUser.setPhone(PHONE);
        UserHolder.set(holderUser);
    }

    @AfterEach
    void cleanup() {
        UserHolder.clear();
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        createdVoucherIds.forEach(id -> {
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void claimSeckillViaNormalEndpointRejected() {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("边界测试秒杀券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(10);
        dto.setMinLevel(0);
        dto.setBeginTime(LocalDateTime.now().plusDays(1).withNano(0));
        dto.setEndTime(LocalDateTime.now().plusDays(2).withNano(0));
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherService.claim(voucherId));
        assertEquals(BaseCode.VOUCHER_NOT_AVAILABLE.getCode(), ex.getCode());
    }
}
