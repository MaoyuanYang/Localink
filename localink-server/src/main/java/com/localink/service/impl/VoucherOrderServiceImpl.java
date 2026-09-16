package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.service.VoucherOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl implements VoucherOrderService {

    private static final int TYPE_SECKILL = 2;
    private static final int STATUS_ON_SHELF = 1;
    private static final int ORDER_STATUS_CREATED = 1;
    private static final int RECONCILIATION_PENDING = 1;

    private final VoucherMapper voucherMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final VoucherOrderMapper voucherOrderMapper;

    @Override
    @Transactional
    public String seckill(Long voucherId) {
        requireSeckillVoucher(voucherId);
        SeckillVoucher seckill = requireOpenSeckill(voucherId);
        requireUserLevel(seckill.getMinLevel());
        requireStock(seckill.getStock());
        requireFirstOrder(voucherId);

        seckillVoucherMapper.deductStock(voucherId);

        VoucherOrder order = new VoucherOrder();
        order.setUserId(UserHolder.get().getId());
        order.setVoucherId(voucherId);
        order.setStatus(ORDER_STATUS_CREATED);
        order.setReconciliationStatus(RECONCILIATION_PENDING);
        voucherOrderMapper.insert(order);
        return String.valueOf(order.getId());
    }

    private void requireSeckillVoucher(Long voucherId) {
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "秒杀券不存在");
        }
        if (voucher.getType() == null || voucher.getType() != TYPE_SECKILL) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "目标券不是秒杀券");
        }
        if (voucher.getStatus() == null || voucher.getStatus() != STATUS_ON_SHELF) {
            throw new LocalinkException(BaseCode.VOUCHER_NOT_AVAILABLE);
        }
    }

    private SeckillVoucher requireOpenSeckill(Long voucherId) {
        SeckillVoucher seckill = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
        if (seckill == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "秒杀券库存信息不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (seckill.getBeginTime() != null && now.isBefore(seckill.getBeginTime())) {
            throw new LocalinkException(BaseCode.SECKILL_NOT_STARTED);
        }
        if (seckill.getEndTime() != null && now.isAfter(seckill.getEndTime())) {
            throw new LocalinkException(BaseCode.SECKILL_ENDED);
        }
        return seckill;
    }

    private void requireUserLevel(Integer minLevel) {
        if (minLevel == null || minLevel <= 0) {
            return;
        }
        Integer level = UserHolder.get().getLevel();
        if (level == null || level < minLevel) {
            throw new LocalinkException(BaseCode.SECKILL_LEVEL_NOT_ENOUGH);
        }
    }

    private void requireStock(Integer stock) {
        if (stock == null || stock < 1) {
            throw new LocalinkException(BaseCode.SECKILL_STOCK_NOT_ENOUGH);
        }
    }

    private void requireFirstOrder(Long voucherId) {
        Long count = voucherOrderMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, UserHolder.get().getId())
                .eq(VoucherOrder::getVoucherId, voucherId));
        if (count != null && count > 0) {
            throw new LocalinkException(BaseCode.SECKILL_DUPLICATE_ORDER);
        }
    }
}
