package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.User;
import com.localink.entity.VoucherOrder;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.reconcile.ReconciliationJob;
import com.localink.framework.seckill.SeckillStockCache;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.mq.SeckillOrderMessage;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M8 审计修复验证（docs/VERIFICATION.md A-1/A-3）：
 * <ul>
 *   <li>A-1：建单撞唯一索引（回流发券给已持券用户）时，先扣的 DB 库存必须同事务回补——幻影扣减归零</li>
 *   <li>A-3：延迟关单任务丢失后的超龄 status=1 挂单，由对账兜底补关单（原会被翻牌为"一致"）</li>
 * </ul>
 */
@SpringBootTest
class AuditFixOrderConsistencyIntegrationTest {

    private static final String PHONE = "13900139881";

    @Autowired
    private ReconciliationJob reconciliationJob;
    @Autowired
    private VoucherOrderService voucherOrderService;
    @Autowired
    private SeckillVoucherService seckillVoucherService;
    @Autowired
    private SmsService smsService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    private VoucherOrderMapper orderMapper;
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
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
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
        createdVoucherIds.forEach(id -> {
            orderMapper.delete(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, id));
            seckillStockCache.evict(id);
            seckillVoucherMapper.delete(new LambdaQueryWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, id));
        });
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        createdVoucherIds.clear();
    }

    @Test
    void duplicateKeyCompensatesPhantomStockDeduction() {
        Long voucherId = createVoucher(5);
        int stockBefore = selectStock(voucherId);

        voucherOrderService.createSeckillOrder(message(voucherId, 1L), "m8-a1-first");
        assertEquals(1, orderCount(voucherId));
        assertEquals(stockBefore - 1, selectStock(voucherId));

        // 回流场景：全新 orderId（幂等标记不命中）撞 uk_user_voucher_active——
        // 修复前：deductStock 已执行且事务照常提交 → DB 库存幻影少 1、无订单
        voucherOrderService.createSeckillOrder(message(voucherId, 2L), "m8-a1-dup");

        assertEquals(1, orderCount(voucherId), "撞唯一索引不得产生新订单");
        assertEquals(stockBefore - 1, selectStock(voucherId), "幻影扣减必须同事务回补（库存不再少 1）");
    }

    @Test
    void overdueCreatedOrderClosedByReconciliationSweep() {
        Long voucherId = createVoucher(5);
        int stockBefore = selectStock(voucherId);

        long orderId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        voucherOrderService.createSeckillOrder(
                new SeckillOrderMessage(orderId, voucherId, 2, userId, orderId + 1, stockBefore,
                        stockBefore - 1), "m8-a3");
        VoucherOrder order = orderMapper.selectById(orderId);
        assertNotNull(order);
        assertEquals(1, order.getStatus());
        assertEquals(stockBefore - 1, selectStock(voucherId));

        // 模拟延迟任务丢失：把订单时间回拨到关单延迟+宽限期之前，保持 status=1
        orderMapper.update(null, new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .set(VoucherOrder::getCreateTime, LocalDateTime.now().minusHours(1)));

        int compensated = reconciliationJob.runOnce();

        VoucherOrder closed = orderMapper.selectById(orderId);
        assertEquals(3, closed.getStatus(), "超龄挂单应由对账补关单裁决关闭");
        assertEquals(stockBefore, selectStock(voucherId), "补关单必须回补 DB 库存");
        org.junit.jupiter.api.Assertions.assertTrue(compensated >= 1,
                "补关单计入差异补偿口径（实际=" + compensated + "）");
    }

    private SeckillOrderMessage message(Long voucherId, long seq) {
        long orderId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId() + seq;
        return new SeckillOrderMessage(orderId, voucherId, 2, userId, orderId + 100, 5, 4);
    }

    private int selectStock(Long voucherId) {
        return seckillVoucherMapper.selectOne(new LambdaQueryWrapper<SeckillVoucher>()
                .eq(SeckillVoucher::getVoucherId, voucherId)).getStock();
    }

    private long orderCount(Long voucherId) {
        return orderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getVoucherId, voucherId));
    }

    private Long createVoucher(int stock) {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M8审计修复券-" + System.nanoTime());
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
