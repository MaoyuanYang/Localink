package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.cache.RedisCache;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.framework.seckill.SeckillStockInitializer;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.service.SeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.6 秒杀 Lua 语义验证：扣减/不足/重复/未预热四返回码、回滚补偿幂等、预热以 DB 为准。
 * M3.12 起 deduct/rollback 返回字符串携账目（'0|traceId|before|after' / '0|before|after'），并同原子落流水。
 */
@SpringBootTest
class SeckillLuaIntegrationTest {

    private static final String USER_A = "90001";
    private static final String USER_B = "90002";
    private static final String TTL = "3600";
    private static final String TRACE_A = "777001";
    private static final String TS = "1760000000000";

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private SeckillStockCache seckillStockCache;

    @Autowired
    private SeckillStockInitializer seckillStockInitializer;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private RedisScript<String> seckillDeductScript;

    @Autowired
    private RedisScript<String> seckillRollbackScript;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    private final List<Long> createdVoucherIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdVoucherIds.forEach(id -> {
            voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
            seckillStockCache.evict(id);
        });
    }

    @Test
    void deductLuaDecrementsStockAndRegistersUser() {
        Long voucherId = createVoucher(10);

        String result = deduct(voucherId, USER_A, TRACE_A);

        assertEquals("0|" + TRACE_A + "|10|9", result);
        assertEquals("9", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)));
        assertTrue(redisCache.sets().isMember(seckillStockCache.orderUsersKey(voucherId), USER_A));
        String flow = redisCache.hashes().entries(seckillStockCache.flowKey(voucherId)).get(TRACE_A);
        assertTrue(flow.contains("\"logType\":1") && flow.contains("\"beforeQty\":10")
                && flow.contains("\"afterQty\":9"), "扣减应同原子落流水: " + flow);
    }

    @Test
    void deductLuaRejectsWhenStockExhausted() {
        Long voucherId = createVoucher(1);
        deduct(voucherId, USER_A, TRACE_A);

        assertEquals("2", deduct(voucherId, USER_B, "777002"));
    }

    @Test
    void deductLuaRejectsDuplicateUserWithoutTouchingStock() {
        Long voucherId = createVoucher(10);
        deduct(voucherId, USER_A, TRACE_A);

        assertEquals("3", deduct(voucherId, USER_A, "777003"));
        assertEquals("9", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)));
    }

    @Test
    void deductLuaReturnsNotWarmedWhenKeyMissing() {
        Long voucherId = createVoucher(10);
        seckillStockCache.evict(voucherId);

        assertEquals("1", deduct(voucherId, USER_A, TRACE_A));
    }

    @Test
    void rollbackLuaRestoresStockAndMembership() {
        Long voucherId = createVoucher(10);
        deduct(voucherId, USER_A, TRACE_A);

        String rollback = rollback(voucherId, USER_A, TRACE_A);

        assertEquals("0|9|10", rollback);
        assertEquals("10", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)));
        assertFalse(redisCache.sets().isMember(seckillStockCache.orderUsersKey(voucherId), USER_A));
        String flow = redisCache.hashes().entries(seckillStockCache.flowKey(voucherId)).get(TRACE_A);
        assertTrue(flow.contains("\"logType\":2") && flow.contains("\"afterQty\":10"),
                "回滚应把流水翻为恢复态: " + flow);
    }

    @Test
    void rollbackLuaIsNoopWhenUserNotInSet() {
        Long voucherId = createVoucher(10);

        String rollback = rollback(voucherId, USER_A, TRACE_A);

        assertEquals("1", rollback);
        assertEquals("10", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)));
    }

    @Test
    void initializerRewarmsStockFromDbValue() throws Exception {
        Long voucherId = createVoucher(10);
        redisCache.strings().set(seckillStockCache.stockKey(voucherId), "3");

        seckillStockInitializer.run(null);

        assertEquals("10", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)),
                "启动回灌应以 DB 当前库存覆盖漂移值");
    }

    @Test
    void warmSkipsEndedActivity() {
        Long voucherId = createVoucher(10);
        seckillStockCache.evict(voucherId);

        seckillStockCache.warm(voucherId, 10, LocalDateTime.now().minusHours(1));

        assertNull(redisCache.strings().getString(seckillStockCache.stockKey(voucherId)));
    }

    private String deduct(Long voucherId, String userId, String traceId) {
        return redisCache.scripts().execute(seckillDeductScript,
                List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId),
                        seckillStockCache.flowKey(voucherId)),
                userId, TTL, traceId, TS);
    }

    private String rollback(Long voucherId, String userId, String traceId) {
        return redisCache.scripts().execute(seckillRollbackScript,
                List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId),
                        seckillStockCache.flowKey(voucherId)),
                userId, traceId, TS);
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.6-Lua券-" + System.nanoTime());
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
