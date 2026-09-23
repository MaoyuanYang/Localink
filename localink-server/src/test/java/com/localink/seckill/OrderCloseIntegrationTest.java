package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.entity.VoucherReconcileLog;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.framework.seckill.SeckillTokenService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-B 订单超时关单验证：延迟任务到期 → 条件关单（status=3+close_time）→ DB/Redis 双库存回流
 * → 资格回滚（出集合+流水翻恢复+恢复行 business_type=2 下单超时）；非创建态订单幂等跳过。
 * 测试属性覆盖 close-delay=500ms 加速到期。
 */
@SpringBootTest(properties = "localink.order.close-delay=500ms")
class OrderCloseIntegrationTest {

    private static final String PHONE = "13900139016";

    @Autowired
    private VoucherOrderService voucherOrderService;

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
            orderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            reconcileLogMapper.delete(new LambdaQueryWrapper<VoucherReconcileLog>()
                    .eq(VoucherReconcileLog::getVoucherId, id));
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, id));
            voucherMapper.deleteById(id);
        });
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void expiredOrderIsClosedAndStockFlowsBackOnBothSides() throws Exception {
        Long voucherId = createVoucher(5);
        String token = seckillTokenService.issue(voucherId, userId);
        String orderId = voucherOrderService.seckill(voucherId, token);
        assertNotNull(OrderAwait.awaitById(orderMapper, Long.valueOf(orderId)));

        // 直接触发关单断言业务闭环。不依赖"延迟自然到期"做断言：全量回归时多个缓存上下文
        // 共享 Kafka 消费组，建单消息可能被别的上下文以其 15m 默认配置投延迟任务，
        // 500ms 任务不存在——延迟到期/分片/重投语义由 delay-starter 测试覆盖
        assertTrue(voucherOrderService.closeOrderIfExpired(Long.valueOf(orderId)), "首次关单应实际执行");
        assertFalse(voucherOrderService.closeOrderIfExpired(Long.valueOf(orderId)), "二次关单幂等跳过");
        VoucherOrder closed = orderMapper.selectById(Long.valueOf(orderId));
        assertEquals(3, closed.getStatus().intValue());
        assertNotNull(closed.getCloseTime(), "关单时间应写入");

        assertEquals(5, seckillVoucherMapper.selectOne(new LambdaQueryWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherId)).getStock().intValue(),
                "DB 库存逆增量回补");
        assertEquals("5", redisCache.strings().getString(seckillStockCache.stockKey(voucherId)),
                "Redis 库存回流");
        assertFalse(redisCache.sets().isMember(seckillStockCache.orderUsersKey(voucherId),
                String.valueOf(userId)), "用户出已购集合（可重抢）");
        VoucherReconcileLog restoreRow = reconcileLogMapper.selectOne(
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(VoucherReconcileLog::getOrderId, Long.valueOf(orderId))
                        .eq(VoucherReconcileLog::getLogType, 2));
        assertNotNull(restoreRow, "关单回流应落恢复行");
        assertEquals(2, restoreRow.getBusinessType().intValue(), "关单归'下单超时'");
        assertTrue(restoreRow.getDetail().contains("ORDER_CLOSE"));
    }

    @Test
    void nonCreatedOrderIsSkippedIdempotently() {
        Long voucherId = createVoucher(5);
        VoucherOrder cancelled = new VoucherOrder();
        cancelled.setId(940_001L);
        cancelled.setUserId(userId);
        cancelled.setVoucherId(voucherId);
        cancelled.setVoucherType(2);
        cancelled.setStatus(2);
        cancelled.setReconciliationStatus(1);
        orderMapper.insert(cancelled);
        try {
            assertFalse(voucherOrderService.closeOrderIfExpired(940_001L),
                    "已取消订单不得被关单任务处置");
            assertEquals(5, seckillVoucherMapper.selectOne(new LambdaQueryWrapper<SeckillVoucher>()
                            .eq(SeckillVoucher::getVoucherId, voucherId)).getStock().intValue(),
                    "库存不得回流（取消处置由用户路径负责）");
        } finally {
            orderMapper.deleteById(940_001L);
        }
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M5关单券-" + System.nanoTime());
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
