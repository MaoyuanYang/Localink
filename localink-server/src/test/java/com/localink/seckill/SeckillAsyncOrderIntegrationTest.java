package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.VoucherOrder;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.mq.SeckillOrderMessage;
import com.localink.service.SeckillVoucherService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.8 异步下单链路：即时返回预生成 orderId，订单由消费端异步落库；
 * orderId 守卫挡重复投递（用同 key 的尾随标记消息证明重复消息已被处理——同分区 FIFO）。
 */
@SpringBootTest
class SeckillAsyncOrderIntegrationTest {

    private static final String PHONE = "13900139011";

    @Autowired
    private com.localink.service.VoucherOrderService voucherOrderService;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private MessageProducer messageProducer;

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
    private Long userId;

    @BeforeEach
    void loginAndSetHolder() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userId = user.getId();
        var holderUser = new com.localink.api.dto.UserDTO();
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
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, "13900139012"));
    }

    @Test
    void seckillReturnsImmediatelyAndOrderPersistsAsynchronously() throws Exception {
        Long voucherId = createVoucher(5);

        String orderId = voucherOrderService.seckill(voucherId);
        assertNotNull(orderId);

        VoucherOrder order = OrderAwait.awaitById(voucherOrderMapper, Long.valueOf(orderId));
        assertNotNull(order, "订单应在窗口内异步落库");
        assertEquals(userId, order.getUserId());
        assertEquals(voucherId, order.getVoucherId());
        assertEquals(2, order.getVoucherType().intValue());
        assertEquals(1, order.getStatus().intValue());
        assertEquals(1, order.getReconciliationStatus().intValue());
        assertEquals(4, selectSeckill(voucherId).getStock());
    }

    @Test
    void duplicateMessageIsGuardedByIdempotentConsumer() throws Exception {
        Long voucherId = createVoucher(5);
        Long userBId = registerSecondUser();

        String firstOrderId = voucherOrderService.seckill(voucherId);
        assertNotNull(OrderAwait.awaitById(voucherOrderMapper, Long.valueOf(firstOrderId)));
        assertEquals(4, selectSeckill(voucherId).getStock());

        // 同 orderId 重复投递（守卫应忽略）+ 尾随标记消息（同 key 同分区 FIFO，标记落库即证明重复消息已被处理）
        messageProducer.sendSync(MqTopics.SECKILL_ORDER, String.valueOf(voucherId),
                new SeckillOrderMessage(Long.valueOf(firstOrderId), voucherId, 2, userId));
        long markerOrderId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        messageProducer.sendSync(MqTopics.SECKILL_ORDER, String.valueOf(voucherId),
                new SeckillOrderMessage(markerOrderId, voucherId, 2, userBId));
        assertNotNull(OrderAwait.awaitById(voucherOrderMapper, markerOrderId), "标记消息应落库");

        Long count = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getVoucherId, voucherId));
        assertEquals(2L, count, "重复消息应被守卫忽略，只新增标记订单");
        assertEquals(3, selectSeckill(voucherId).getStock());
    }

    private Long registerSecondUser() {
        String phoneB = "13900139012";
        smsService.sendCode(phoneB);
        String codeB = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, phoneB));
        String tokenB = userService.login(phoneB, codeB);
        issuedTokens.add(tokenB);
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phoneB));
        User userB = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phoneB));
        return userB.getId();
    }

    private SeckillVoucher selectSeckill(Long voucherId) {
        return seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.8异步券-" + System.nanoTime());
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
