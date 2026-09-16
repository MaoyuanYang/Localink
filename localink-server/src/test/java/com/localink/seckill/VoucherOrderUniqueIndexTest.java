package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.VoucherOrder;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.service.SeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * uk_user_voucher_active 条件唯一索引语义验证：秒杀活跃订单（voucher_type=2 且 status=1）每对唯一，
 * 已取消/关闭订单与普通券订单（生成列 NULL）不占位——允许再抢与重复领券。
 */
@SpringBootTest
class VoucherOrderUniqueIndexTest {

    private static final int TYPE_NORMAL = 1;
    private static final int TYPE_SECKILL = 2;
    private static final int STATUS_CREATED = 1;
    private static final int STATUS_CANCELLED = 2;

    @Autowired
    private SeckillVoucherService seckillVoucherService;

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
        });
    }

    @Test
    void secondActiveSeckillOrderForSameUserVoucherRejected() {
        Long voucherId = createSeckillVoucher();
        long userId = System.nanoTime();

        voucherOrderMapper.insert(order(userId, voucherId, TYPE_SECKILL, STATUS_CREATED));

        assertThrows(DuplicateKeyException.class,
                () -> voucherOrderMapper.insert(order(userId, voucherId, TYPE_SECKILL, STATUS_CREATED)));
    }

    @Test
    void cancelledSeckillOrderDoesNotBlockRebuyAndRepeatedCancellationsCoexist() {
        Long voucherId = createSeckillVoucher();
        long userId = System.nanoTime();

        voucherOrderMapper.insert(order(userId, voucherId, TYPE_SECKILL, STATUS_CANCELLED));
        voucherOrderMapper.insert(order(userId, voucherId, TYPE_SECKILL, STATUS_CREATED));
        voucherOrderMapper.insert(order(userId, voucherId, TYPE_SECKILL, STATUS_CANCELLED));

        Long count = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId));
        assertEquals(3L, count);
    }

    @Test
    void normalVoucherAllowsRepeatedActiveClaims() {
        Long voucherId = createSeckillVoucher();
        long userId = System.nanoTime();

        voucherOrderMapper.insert(order(userId, voucherId, TYPE_NORMAL, STATUS_CREATED));
        voucherOrderMapper.insert(order(userId, voucherId, TYPE_NORMAL, STATUS_CREATED));

        Long count = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId));
        assertEquals(2L, count, "普通券不参与一人一单，同用户可重复领取");
    }

    private VoucherOrder order(long userId, Long voucherId, int voucherType, int status) {
        VoucherOrder order = new VoucherOrder();
        order.setId(System.nanoTime());
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setVoucherType(voucherType);
        order.setStatus(status);
        order.setReconciliationStatus(1);
        return order;
    }

    private Long createSeckillVoucher() {
        SeckillVoucherDTO dto = new SeckillVoucherDTO();
        dto.setShopId(1L);
        dto.setTitle("M3.5唯一索引券-" + System.nanoTime());
        dto.setPayValue(100L);
        dto.setActualValue(10000L);
        dto.setStock(10);
        dto.setMinLevel(0);
        dto.setBeginTime(LocalDateTime.now().minusHours(1).withNano(0));
        dto.setEndTime(LocalDateTime.now().plusHours(1).withNano(0));
        Long voucherId = Long.valueOf(seckillVoucherService.create(dto));
        createdVoucherIds.add(voucherId);
        return voucherId;
    }
}
