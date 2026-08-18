package com.localink.voucher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.UserDTO;
import com.localink.api.dto.VoucherDTO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.SmsConstants;
import com.localink.constant.UserConstants;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import com.localink.service.VoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class VoucherClaimIntegrationTest {

    private static final String PHONE = "13900139005";

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void loginAndSetHolder() {
        smsService.sendCode(PHONE);
        String code = stringRedisTemplate.opsForValue().get(SmsConstants.CODE_KEY_PREFIX + PHONE);
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userId = user.getId();
        UserDTO holderUser = new UserDTO();
        holderUser.setId(userId);
        holderUser.setPhone(PHONE);
        UserHolder.set(holderUser);
    }

    @AfterEach
    void cleanup() {
        UserHolder.clear();
        stringRedisTemplate.delete(SmsConstants.CODE_KEY_PREFIX + PHONE);
        issuedTokens.forEach(token -> stringRedisTemplate.delete(UserConstants.TOKEN_KEY_PREFIX + token));
        createdVoucherIds.forEach(voucherId ->
                voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, voucherId)));
        createdVoucherIds.forEach(voucherMapper::deleteById);
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    private Long createVoucher() {
        VoucherDTO dto = new VoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("领取测试券-" + System.nanoTime());
        dto.setPayValue(0L);
        dto.setActualValue(1000L);
        Long id = Long.valueOf(voucherService.create(dto));
        createdVoucherIds.add(id);
        return id;
    }

    @Test
    void claimCreatesOrderForCurrentUser() {
        Long voucherId = createVoucher();

        Long orderId = Long.valueOf(voucherService.claim(voucherId));

        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        assertEquals(userId, order.getUserId());
        assertEquals(voucherId, order.getVoucherId());
        assertEquals(1, order.getStatus());
        assertEquals(1, order.getReconciliationStatus());
    }

    @Test
    void repeatedClaimCreatesSecondOrder() {
        Long voucherId = createVoucher();

        voucherService.claim(voucherId);
        voucherService.claim(voucherId);

        Long count = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, voucherId));
        assertEquals(2L, count);
    }

    @Test
    void claimOffShelfVoucherRejected() {
        Long voucherId = createVoucher();
        Voucher off = new Voucher();
        off.setId(voucherId);
        off.setStatus(2);
        voucherMapper.updateById(off);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherService.claim(voucherId));
        assertEquals(BaseCode.VOUCHER_NOT_AVAILABLE.getCode(), ex.getCode());
    }
}
