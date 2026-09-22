package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.cache.RedisCache;
import com.localink.entity.RollbackFailureLog;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.mapper.RollbackFailureLogMapper;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mq.MessageEnvelope;
import com.localink.mq.MqTopics;
import com.localink.mq.SeckillOrderMessage;
import com.localink.service.SeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.11 消费可靠性：超龄丢弃（未建单回滚资格 / 已建单跳过不动账）、重试耗尽回滚、失败表映射。
 * 超龄消息用 KafkaTemplate 直发手工构造的旧时间戳信封（producer 会打新时间戳，无法注入超龄）。
 */
@SpringBootTest
class SeckillReliabilityIntegrationTest {

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private VoucherMapper voucherMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private RollbackFailureLogMapper rollbackFailureLogMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private SeckillStockCache seckillStockCache;

    @Autowired
    private RedisScript<Long> seckillDeductScript;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private final List<Long> createdVoucherIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdVoucherIds.forEach(id -> {
            voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
            seckillStockCache.evict(id);
            rollbackFailureLogMapper.delete(new LambdaQueryWrapper<RollbackFailureLog>()
                    .eq(RollbackFailureLog::getVoucherId, id));
        });
    }

    @Test
    void staleMessageWithoutOrderRollsBackQualification() throws Exception {
        Long voucherId = createVoucher(5);
        Long userId = 910_001L;
        assertEquals(0L, deductInRedis(voucherId, userId));
        assertEquals("4", redisStock(voucherId));

        sendRawMessage(new SeckillOrderMessage(IdWorkerId.next(), voucherId, 2, userId),
                System.currentTimeMillis() - 60_000);

        assertTrue(awaitRedisState(voucherId, "5", userId, false), "超龄未建单应回滚：库存回 5、用户出集合");
        assertEquals(0L, orderCount(voucherId));
    }

    @Test
    void staleMessageWithExistingOrderSkipsWithoutTouchingRedis() throws Exception {
        Long voucherId = createVoucher(5);
        Long userId = 910_002L;
        assertEquals(0L, deductInRedis(voucherId, userId));
        Long orderId = IdWorkerId.next();
        insertOrder(orderId, voucherId, userId);
        assertEquals("4", redisStock(voucherId));

        sendRawMessage(new SeckillOrderMessage(orderId, voucherId, 2, userId),
                System.currentTimeMillis() - 60_000);

        Thread.sleep(3000);
        assertEquals("4", redisStock(voucherId), "订单已落库的超龄消息不得回滚");
        assertTrue(redisCache.sets().isMember(seckillStockCache.orderUsersKey(voucherId), String.valueOf(userId)),
                "用户不得被移出已购集合");
        assertEquals(1L, orderCount(voucherId));
    }

    @Test
    void exhaustedRetriesRollBackQualification() throws Exception {
        Long voucherId = createVoucher(1);
        Long userId = 910_003L;
        setDbStock(voucherId, 0);
        assertEquals(0L, deductInRedis(voucherId, userId), "构造分歧态：Redis 扣到 0、DB 已是 0");

        kafkaTemplate.send(MqTopics.SECKILL_ORDER, String.valueOf(voucherId), envelopeJson(
                new SeckillOrderMessage(IdWorkerId.next(), voucherId, 2, userId), System.currentTimeMillis()));

        assertTrue(awaitRedisState(voucherId, "1", userId, false),
                "重试耗尽（4 次尝试+退避约 1.4s）后应回滚：库存回 1、用户出集合");
        assertEquals(0L, orderCount(voucherId), "分歧态下永远建不成单");
        assertEquals(0L, rollbackFailureLogMapper.selectCount(new LambdaQueryWrapper<RollbackFailureLog>()
                        .eq(RollbackFailureLog::getVoucherId, voucherId)),
                "回滚成功不应落失败表");
    }

    @Test
    void rollbackFailureLogMapperRoundTrips() {
        Long voucherId = createVoucher(5);
        RollbackFailureLog log = new RollbackFailureLog();
        log.setVoucherId(voucherId);
        log.setUserId(910_004L);
        log.setRetryAttempts(3);
        log.setSource("TEST");
        log.setDetail("mapper round trip");
        rollbackFailureLogMapper.insert(log);

        RollbackFailureLog loaded = rollbackFailureLogMapper.selectById(log.getId());

        assertNotNull(loaded);
        assertEquals(voucherId, loaded.getVoucherId());
        assertEquals(910_004L, loaded.getUserId());
        assertEquals(3, loaded.getRetryAttempts());
        assertEquals("TEST", loaded.getSource());
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.11可靠性券-" + System.nanoTime());
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

    private Long deductInRedis(Long voucherId, Long userId) {
        return redisCache.scripts().execute(seckillDeductScript,
                List.of(seckillStockCache.stockKey(voucherId), seckillStockCache.orderUsersKey(voucherId)),
                String.valueOf(userId), "3600");
    }

    private String redisStock(Long voucherId) {
        return redisCache.strings().getString(seckillStockCache.stockKey(voucherId));
    }

    private void sendRawMessage(SeckillOrderMessage message, long timestamp) {
        kafkaTemplate.send(MqTopics.SECKILL_ORDER, String.valueOf(message.voucherId()),
                envelopeJson(message, timestamp));
    }

    private String envelopeJson(SeckillOrderMessage message, long timestamp) {
        return com.alibaba.fastjson2.JSON.toJSONString(
                new MessageEnvelope<>(java.util.UUID.randomUUID().toString(),
                        String.valueOf(message.voucherId()), null, timestamp, message));
    }

    private void setDbStock(Long voucherId, int stock) {
        SeckillVoucher update = new SeckillVoucher();
        SeckillVoucher current = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
        update.setId(current.getId());
        update.setStock(stock);
        seckillVoucherMapper.updateById(update);
    }

    private void insertOrder(Long orderId, Long voucherId, Long userId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setVoucherType(2);
        order.setStatus(1);
        order.setReconciliationStatus(1);
        voucherOrderMapper.insert(order);
    }

    private Long orderCount(Long voucherId) {
        return voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getVoucherId, voucherId));
    }

    private boolean awaitRedisState(Long voucherId, String expectedStock, Long userId, boolean expectMember)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            boolean stockOk = expectedStock.equals(redisStock(voucherId));
            boolean memberOk = redisCache.sets().isMember(seckillStockCache.orderUsersKey(voucherId),
                    String.valueOf(userId)) == expectMember;
            if (stockOk && memberOk) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static final class IdWorkerId {
        private static long seq = 920_000_000L;

        static Long next() {
            return seq++;
        }
    }
}
