package com.localink.seckill;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.VoucherOrder;
import com.localink.entity.VoucherReconcileLog;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mapper.VoucherReconcileLogMapper;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import com.localink.service.VoucherOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.12 对账日志三层落账验证：Redis 流水（Lua 同原子写/翻新）↔ DB 流水行（扣减随建单事务、恢复随回滚）↔ 订单
 * （reconciliation_status 起维护）。为 M5.1 定时比对"Redis 扣了但 DB 无单"铺数据地基。
 */
@SpringBootTest
class SeckillReconcileIntegrationTest {

    private static final String PHONE = "13900139013";

    @Autowired
    private VoucherOrderService voucherOrderService;

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
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private VoucherReconcileLogMapper voucherReconcileLogMapper;

    @Autowired
    private UserMapper userMapper;

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
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
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
            voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            voucherReconcileLogMapper.delete(new LambdaQueryWrapper<VoucherReconcileLog>()
                    .eq(VoucherReconcileLog::getVoucherId, id));
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void seckillWritesRedisFlowAndDbDeductLogWithSameTrace() throws Exception {
        Long voucherId = createVoucher(10);

        String orderId = voucherOrderService.seckill(voucherId);

        VoucherOrder order = OrderAwait.awaitById(voucherOrderMapper, Long.valueOf(orderId));
        assertNotNull(order, "订单应异步落库");
        assertEquals(1, order.getReconciliationStatus(), "订单对账状态起维护（1 待处理）");

        Map<String, String> flow = redisCache.hashes().entries(seckillStockCache.flowKey(voucherId));
        assertEquals(1, flow.size(), "一笔扣减一条 Redis 流水");
        JSONObject flowJson = JSONObject.parseObject(flow.values().iterator().next());
        assertEquals(1, flowJson.getIntValue("logType"));
        assertEquals(10, flowJson.getIntValue("beforeQty"));
        assertEquals(9, flowJson.getIntValue("afterQty"));
        assertEquals(-1, flowJson.getIntValue("changeQty"));

        VoucherReconcileLog deductLog = voucherReconcileLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getOrderId, Long.valueOf(orderId))
                        .eq(VoucherReconcileLog::getLogType, 1));
        assertNotNull(deductLog, "扣减流水行应随建单事务落库");
        assertEquals(Long.valueOf(orderId), deductLog.getOrderId());
        assertEquals(userId, deductLog.getUserId());
        assertEquals(voucherId, deductLog.getVoucherId());
        assertEquals(flowJson.getLongValue("traceId"), deductLog.getTraceId(),
                "Redis 流水与 DB 流水行共享同一 traceId（跨层串联的锚点）");
        assertEquals(1, deductLog.getBusinessType());
        assertEquals(-1, deductLog.getChangeQty());
        assertEquals(10, deductLog.getBeforeQty());
        assertEquals(9, deductLog.getAfterQty());
        assertEquals(1, deductLog.getReconciliationStatus());
        assertNotNull(deductLog.getMessageId(), "扣减行携带 Kafka 消息 UUID");
    }

    @Test
    void rollbackFlipsRedisFlowAndWritesRestoreLogOnce() {
        Long voucherId = createVoucher(5);
        Long orderId = IdWorker.getId();
        Long traceId = IdWorker.getId();
        String result = redisCache.scripts().execute(seckillDeductScript,
                List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId),
                        seckillStockCache.flowKey(voucherId)),
                String.valueOf(userId), "3600", String.valueOf(traceId),
                String.valueOf(System.currentTimeMillis()));
        assertTrue(result.startsWith("0|"), "构造已扣减态: " + result);

        voucherOrderService.rollbackSeckillQualification(voucherId, userId, orderId, traceId,
                "REQUEST_SEND", "reconcile test simulated send failure");

        JSONObject flowJson = JSONObject.parseObject(
                redisCache.hashes().entries(seckillStockCache.flowKey(voucherId)).get(String.valueOf(traceId)));
        assertEquals(2, flowJson.getIntValue("logType"), "回滚应把 Redis 流水翻为恢复态");
        assertEquals(5, flowJson.getIntValue("afterQty"), "库存应回到 5");

        VoucherReconcileLog restoreLog = voucherReconcileLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getOrderId, orderId)
                        .eq(VoucherReconcileLog::getLogType, 2));
        assertNotNull(restoreLog, "回滚成功应落恢复流水行");
        assertEquals(3, restoreLog.getBusinessType(), "REQUEST_SEND 归'下单失败'");
        assertEquals(1, restoreLog.getChangeQty());
        assertEquals(traceId, restoreLog.getTraceId());

        // 幂等重试：资格已退（用户不在集合），Lua 返回无需补偿，不得再落恢复行
        voucherOrderService.rollbackSeckillQualification(voucherId, userId, orderId, traceId,
                "REQUEST_SEND", "reconcile test retry");
        Long restoreCount = voucherReconcileLogMapper.selectCount(new LambdaQueryWrapper<VoucherReconcileLog>()
                .eq(VoucherReconcileLog::getVoucherId, voucherId)
                .eq(VoucherReconcileLog::getLogType, 2));
        assertEquals(1L, restoreCount, "重试回滚不得双写恢复行");
        assertEquals("5", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)));
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.12对账券-" + System.nanoTime());
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
