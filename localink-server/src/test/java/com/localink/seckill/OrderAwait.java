package com.localink.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.entity.VoucherOrder;
import com.localink.mapper.VoucherOrderMapper;

/**
 * 异步建单的落库等待工具：下单即时返回 orderId，订单由消费端异步写入（本地 broker 通常 <1s，窗口 5s）。
 */
final class OrderAwait {

    private static final long TIMEOUT_MS = 5000;
    private static final long POLL_INTERVAL_MS = 100;

    private OrderAwait() {
    }

    static VoucherOrder awaitById(VoucherOrderMapper mapper, Long orderId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        VoucherOrder order = null;
        while (System.currentTimeMillis() < deadline) {
            order = mapper.selectById(orderId);
            if (order != null) {
                return order;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return order;
    }

    static boolean awaitCountByVoucher(VoucherOrderMapper mapper, Long voucherId, long expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Long count = mapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getVoucherId, voucherId));
            if (count != null && count == expected) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }
}
