package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.api.dto.VoucherDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import com.localink.service.VoucherOrderService;
import com.localink.service.VoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class SeckillOrderIntegrationTest {

    private static final String PHONE = "13900139009";

    private static final int ORDER_STATUS_CREATED = 1;
    private static final int RECONCILIATION_PENDING = 1;

    @Autowired
    private VoucherOrderService voucherOrderService;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SeckillStockCache seckillStockCache;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private final List<String> createdRacePhones = new ArrayList<>();

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
            voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        createdRacePhones.forEach(phone ->
                userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, phone)));
    }

    @Test
    void seckillCreatesOrderAndDecrementsStock() {
        UserHolder.get().setLevel(2);
        Long voucherId = createSeckillVoucher(10, 1, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));

        String orderId = voucherOrderService.seckill(voucherId);

        VoucherOrder order = voucherOrderMapper.selectById(Long.valueOf(orderId));
        assertNotNull(order);
        assertEquals(UserHolder.get().getId(), order.getUserId());
        assertEquals(voucherId, order.getVoucherId());
        assertEquals(ORDER_STATUS_CREATED, order.getStatus());
        assertEquals(RECONCILIATION_PENDING, order.getReconciliationStatus());

        SeckillVoucher seckill = selectSeckill(voucherId);
        assertEquals(9, seckill.getStock());
        Long orderCount = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, UserHolder.get().getId())
                .eq(VoucherOrder::getVoucherId, voucherId));
        assertEquals(1L, orderCount);
    }

    @Test
    void seckillRejectedBeforeBeginTime() {
        Long voucherId = createSeckillVoucher(10, 0, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(voucherId));
        assertEquals(BaseCode.SECKILL_NOT_STARTED.getCode(), ex.getCode());
        assertEquals(10, selectSeckill(voucherId).getStock());
    }

    @Test
    void seckillRejectedAfterEndTime() {
        Long voucherId = createSeckillVoucher(10, 0, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(voucherId));
        assertEquals(BaseCode.SECKILL_ENDED.getCode(), ex.getCode());
        assertEquals(10, selectSeckill(voucherId).getStock());
    }

    @Test
    void seckillRejectedWhenStockExhausted() {
        Long voucherId = createSeckillVoucher(1, 0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        voucherOrderService.seckill(voucherId);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(voucherId));
        assertEquals(BaseCode.SECKILL_STOCK_NOT_ENOUGH.getCode(), ex.getCode());
        assertEquals(0, selectSeckill(voucherId).getStock());
    }

    @Test
    void seckillRejectedForDuplicateOrder() {
        Long voucherId = createSeckillVoucher(10, 0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        voucherOrderService.seckill(voucherId);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(voucherId));
        assertEquals(BaseCode.SECKILL_DUPLICATE_ORDER.getCode(), ex.getCode());
        assertEquals(9, selectSeckill(voucherId).getStock());
    }

    @Test
    void seckillRejectedWhenLevelInsufficient() {
        UserHolder.get().setLevel(2);
        Long voucherId = createSeckillVoucher(10, 5, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(voucherId));
        assertEquals(BaseCode.SECKILL_LEVEL_NOT_ENOUGH.getCode(), ex.getCode());
        assertEquals(10, selectSeckill(voucherId).getStock());
    }

    @Test
    void seckillRejectedWhenVoucherMissing() {
        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(999999L));
        assertEquals(BaseCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void seckillRejectedForNormalVoucher() {
        VoucherDTO dto = new VoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.1普通券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        Long voucherId = Long.valueOf(voucherService.create(dto));
        createdVoucherIds.add(voucherId);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(voucherId));
        assertEquals(BaseCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void seckillRejectedWhenOffShelf() {
        Long voucherId = createSeckillVoucher(10, 0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        Voucher offShelf = new Voucher();
        offShelf.setId(voucherId);
        offShelf.setStatus(0);
        voucherMapper.updateById(offShelf);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> voucherOrderService.seckill(voucherId));
        assertEquals(BaseCode.VOUCHER_NOT_AVAILABLE.getCode(), ex.getCode());
        assertEquals(10, selectSeckill(voucherId).getStock());
    }

    @Test
    void deductStockGuardReturnsZeroWhenStockEmpty() {
        Long voucherId = createSeckillVoucher(1, 0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));

        assertEquals(1, seckillVoucherMapper.deductStock(voucherId));
        assertEquals(0, selectSeckill(voucherId).getStock());

        assertEquals(0, seckillVoucherMapper.deductStock(voucherId));
        assertEquals(0, selectSeckill(voucherId).getStock());
    }

    @Test
    void concurrentSameUserSeckillYieldsExactlyOneOrder() throws Exception {
        Long voucherId = createSeckillVoucher(10, 0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        Long userId = UserHolder.get().getId();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<LocalinkException>> futures = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    UserDTO holderUser = new UserDTO();
                    holderUser.setId(userId);
                    holderUser.setLevel(0);
                    UserHolder.set(holderUser);
                    start.await();
                    try {
                        voucherOrderService.seckill(voucherId);
                        return null;
                    } catch (LocalinkException e) {
                        return e;
                    } finally {
                        UserHolder.clear();
                    }
                }));
            }
            start.countDown();
            int success = 0;
            int duplicateRejected = 0;
            for (Future<LocalinkException> future : futures) {
                LocalinkException e = future.get(30, TimeUnit.SECONDS);
                if (e == null) {
                    success++;
                } else if (Integer.valueOf(BaseCode.SECKILL_DUPLICATE_ORDER.getCode()).equals(e.getCode())) {
                    duplicateRejected++;
                }
            }
            assertEquals(1, success, "同一用户并发应恰好成交一单");
            assertEquals(threads - 1, duplicateRejected);
            assertEquals(1L, voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getVoucherId, voucherId)));
            assertEquals(9, selectSeckill(voucherId).getStock());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSeckillOnLastStockDeductsExactlyOnce() throws Exception {
        Long voucherId = createSeckillVoucher(1, 0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<LocalinkException>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                User user = new User();
                user.setPhone("1390013920" + i);
                user.setNickName("M3.2并发用户" + i);
                userMapper.insert(user);
                createdRacePhones.add(user.getPhone());
                futures.add(pool.submit(() -> {
                    UserDTO holderUser = new UserDTO();
                    holderUser.setId(user.getId());
                    holderUser.setLevel(0);
                    UserHolder.set(holderUser);
                    try {
                        voucherOrderService.seckill(voucherId);
                        return null;
                    } catch (LocalinkException e) {
                        return e;
                    } finally {
                        UserHolder.clear();
                    }
                }));
            }
            int success = 0;
            int stockRejected = 0;
            for (Future<LocalinkException> future : futures) {
                LocalinkException e = future.get(30, TimeUnit.SECONDS);
                if (e == null) {
                    success++;
                } else if (Integer.valueOf(BaseCode.SECKILL_STOCK_NOT_ENOUGH.getCode()).equals(e.getCode())) {
                    stockRejected++;
                }
            }
            assertEquals(1, success);
            assertEquals(threads - 1, stockRejected);
            assertEquals(0, selectSeckill(voucherId).getStock());
            assertEquals(1L, voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getVoucherId, voucherId)));
        } finally {
            pool.shutdownNow();
        }
    }

    private Long createSeckillVoucher(int stock, int minLevel, LocalDateTime begin, LocalDateTime end) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.1秒杀券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(stock);
        dto.setMinLevel(minLevel);
        dto.setBeginTime(begin.withNano(0));
        dto.setEndTime(end.withNano(0));
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);
        return voucherId;
    }

    private SeckillVoucher selectSeckill(Long voucherId) {
        return seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
    }
}
