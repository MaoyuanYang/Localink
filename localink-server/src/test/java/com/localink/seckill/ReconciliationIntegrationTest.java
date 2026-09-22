package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.RollbackFailureLog;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.entity.VoucherReconcileLog;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.reconcile.ReconciliationJob;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.framework.seckill.SeckillTokenService;
import com.localink.mapper.RollbackFailureLogMapper;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mapper.VoucherReconcileLogMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-A 对账体系验证：一致翻牌（PENDING→一致）、差异补偿（Redis 扣了 DB 无单 → 资格回滚）、
 * 宽限期保护（建单在途不动账）、回滚失败表重试收敛。
 */
@SpringBootTest
class ReconciliationIntegrationTest {

    private static final String PHONE = "13900139015";

    @Autowired
    private ReconciliationJob reconciliationJob;

    @Autowired
    private com.localink.service.VoucherOrderService voucherOrderService;

    @Autowired
    private SeckillTokenService seckillTokenService;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

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
    private VoucherReconcileLogMapper reconcileLogMapper;

    @Autowired
    private RollbackFailureLogMapper rollbackFailureLogMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private SeckillStockCache seckillStockCache;

    @Autowired
    private RedisScript<String> seckillDeductScript;

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
            int t = (int) Math.floorMod(id, 2);
            orderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            reconcileLogMapper.delete(new LambdaQueryWrapper<VoucherReconcileLog>()
                    .eq(VoucherReconcileLog::getVoucherId, id));
            rollbackFailureLogMapper.delete(new LambdaQueryWrapper<RollbackFailureLog>()
                    .eq(RollbackFailureLog::getVoucherId, id));
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void consistentOrderFlipsToConsistentStatus() throws Exception {
        Long voucherId = createVoucher(5);
        String token = seckillTokenService.issue(voucherId, userId);
        String orderId = voucherOrderService.seckill(voucherId, token);

        OrderAwait.awaitById(orderMapper, Long.valueOf(orderId));
        reconciliationJob.runOnce();

        VoucherOrder order = orderMapper.selectById(Long.valueOf(orderId));
        assertEquals(4, order.getReconciliationStatus().intValue(), "一致订单应翻牌为 4（一致）");
        VoucherReconcileLog deductRow = reconcileLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getOrderId, Long.valueOf(orderId))
                        .eq(VoucherReconcileLog::getLogType, 1));
        assertEquals(4, deductRow.getReconciliationStatus().intValue(), "扣减流水行同步翻牌");
    }

    @Test
    void staleRedisOnlyDeductionGetsCompensated() {
        Long voucherId = createVoucher(5);
        Long traceId = 555_001L;
        deductInRedis(voucherId, userId, traceId, System.currentTimeMillis() - 5 * 60_000);

        int compensated = reconciliationJob.runOnce();

        assertEquals(1, compensated, "应识别并补偿一笔差异");
        assertEquals("5", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)),
                "资格回滚：库存回到 5");
        assertTrue(!redisCache.sets().isMember(seckillStockCache.orderUsersKey(voucherId),
                String.valueOf(userId)), "资格回滚：用户出集合");
        String flow = redisCache.hashes().entries(seckillStockCache.flowKey(voucherId))
                .get(String.valueOf(traceId));
        assertTrue(flow.contains("\"logType\":2"), "Redis 流水翻恢复态: " + flow);
        VoucherReconcileLog restoreRow = reconcileLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getVoucherId, voucherId)
                        .eq(VoucherReconcileLog::getLogType, 2));
        assertNotNull(restoreRow, "应落 DB 恢复行");
        assertEquals(traceId, restoreRow.getOrderId(), "orderId 缺失时以 traceId 顶替落账");
        assertTrue(restoreRow.getDetail().contains("RECONCILE"), "detail 留痕补偿来源");
        assertEquals(0L, orderCount(voucherId), "差异本无订单");

        int secondRun = reconciliationJob.runOnce();
        assertEquals(0, secondRun, "流水已翻恢复态，二次对账不再动作");
        Long restoreRows = reconcileLogMapper.selectCount(new LambdaQueryWrapper<VoucherReconcileLog>()
                .eq(VoucherReconcileLog::getVoucherId, voucherId)
                .eq(VoucherReconcileLog::getLogType, 2));
        assertEquals(1L, restoreRows, "不双写恢复行");
    }

    @Test
    void withinGraceDeductionIsUntouched() {
        Long voucherId = createVoucher(5);
        Long traceId = 555_002L;
        deductInRedis(voucherId, userId, traceId, System.currentTimeMillis());

        int compensated = reconciliationJob.runOnce();

        assertEquals(0, compensated, "宽限期内视为建单在途，不动账");
        assertEquals("4", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)),
                "Redis 保持已扣减态");
        assertNull(reconcileLogMapper.selectOne(new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getVoucherId, voucherId)
                        .eq(VoucherReconcileLog::getLogType, 2)),
                "不落恢复行");
    }

    @Test
    void rollbackFailureRowConvergesAfterRetry() {
        Long voucherId = createVoucher(5);
        RollbackFailureLog failure = new RollbackFailureLog();
        failure.setVoucherId(voucherId);
        failure.setUserId(930_001L);
        failure.setOrderId(null);
        failure.setTraceId(555_003L);
        failure.setRetryAttempts(1);
        failure.setSource("REQUEST_SEND");
        failure.setDetail("test seeded failure");
        rollbackFailureLogMapper.insert(failure);

        reconciliationJob.runOnce();

        assertNull(rollbackFailureLogMapper.selectById(failure.getId()),
                "用户不在已购集合 → Lua 无需补偿 → 重试即收敛删行");
    }

    private void deductInRedis(Long voucherId, Long userId, Long traceId, long tsMillis) {
        String result = redisCache.scripts().execute(seckillDeductScript,
                List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId),
                        seckillStockCache.flowKey(voucherId)),
                String.valueOf(userId), "3600", String.valueOf(traceId), String.valueOf(tsMillis));
        assertTrue(result.startsWith("0|"), "构造已扣减态: " + result);
    }

    private Long orderCount(Long voucherId) {
        return orderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getVoucherId, voucherId));
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M5对账券-" + System.nanoTime());
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
