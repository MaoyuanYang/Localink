package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.VoucherDTO;
import com.localink.api.vo.VoucherVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.Voucher;
import com.localink.entity.VoucherOrder;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.VoucherMapper;
import com.localink.mapper.VoucherOrderMapper;
import com.localink.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VoucherServiceImpl implements VoucherService {

    private static final int TYPE_NORMAL = 1;
    private static final int STATUS_ON_SHELF = 1;
    private static final int ORDER_STATUS_CREATED = 1;
    private static final int RECONCILIATION_PENDING = 1;

    private final VoucherMapper voucherMapper;
    private final VoucherOrderMapper voucherOrderMapper;

    @Override
    public List<VoucherVO> listByShop(Long shopId) {
        List<Voucher> vouchers = voucherMapper.selectList(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getShopId, shopId)
                .eq(Voucher::getStatus, STATUS_ON_SHELF)
                .orderByAsc(Voucher::getId));
        return vouchers.stream().map(this::toVO).toList();
    }

    @Override
    public VoucherVO detail(Long id) {
        return toVO(requireExists(id));
    }

    @Override
    public String create(VoucherDTO dto) {
        Voucher voucher = new Voucher();
        BeanUtils.copyProperties(dto, voucher, "id");
        voucher.setType(TYPE_NORMAL);
        if (voucher.getStatus() == null) {
            voucher.setStatus(STATUS_ON_SHELF);
        }
        voucherMapper.insert(voucher);
        return String.valueOf(voucher.getId());
    }

    @Override
    public void update(VoucherDTO dto) {
        if (dto.getId() == null) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "更新操作缺少 id");
        }
        requireExists(dto.getId());
        Voucher voucher = new Voucher();
        BeanUtils.copyProperties(dto, voucher);
        voucherMapper.updateById(voucher);
    }

    @Override
    public void delete(Long id) {
        requireExists(id);
        voucherMapper.deleteById(id);
    }

    @Override
    public String claim(Long voucherId) {
        Voucher voucher = requireExists(voucherId);
        if (voucher.getType() == null || voucher.getType() != TYPE_NORMAL
                || voucher.getStatus() == null || voucher.getStatus() != STATUS_ON_SHELF) {
            throw new LocalinkException(BaseCode.VOUCHER_NOT_AVAILABLE);
        }
        VoucherOrder order = new VoucherOrder();
        order.setUserId(UserHolder.get().getId());
        order.setVoucherId(voucherId);
        order.setStatus(ORDER_STATUS_CREATED);
        order.setReconciliationStatus(RECONCILIATION_PENDING);
        voucherOrderMapper.insert(order);
        return String.valueOf(order.getId());
    }

    private Voucher requireExists(Long id) {
        Voucher voucher = voucherMapper.selectById(id);
        if (voucher == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "优惠券不存在");
        }
        return voucher;
    }

    private VoucherVO toVO(Voucher voucher) {
        VoucherVO vo = new VoucherVO();
        BeanUtils.copyProperties(voucher, vo);
        return vo;
    }
}
