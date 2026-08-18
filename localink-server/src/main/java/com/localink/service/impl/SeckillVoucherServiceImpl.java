package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.vo.SeckillVoucherVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.SeckillVoucher;
import com.localink.entity.Voucher;
import com.localink.mapper.SeckillVoucherMapper;
import com.localink.mapper.VoucherMapper;
import com.localink.service.SeckillVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeckillVoucherServiceImpl implements SeckillVoucherService {

    private static final int TYPE_SECKILL = 2;
    private static final int STATUS_ON_SHELF = 1;

    private final VoucherMapper voucherMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;

    @Override
    @Transactional
    public String create(SeckillVoucherDTO dto) {
        requireTimeOrder(dto);
        Voucher voucher = new Voucher();
        voucher.setShopId(dto.getShopId());
        voucher.setTitle(dto.getTitle());
        voucher.setSubTitle(dto.getSubTitle());
        voucher.setRules(dto.getRules());
        voucher.setPayValue(dto.getPayValue());
        voucher.setActualValue(dto.getActualValue());
        voucher.setType(TYPE_SECKILL);
        voucher.setStatus(dto.getStatus() == null ? STATUS_ON_SHELF : dto.getStatus());
        voucherMapper.insert(voucher);

        SeckillVoucher seckill = new SeckillVoucher();
        seckill.setVoucherId(voucher.getId());
        seckill.setInitStock(dto.getStock());
        seckill.setStock(dto.getStock());
        seckill.setMinLevel(dto.getMinLevel());
        seckill.setBeginTime(dto.getBeginTime());
        seckill.setEndTime(dto.getEndTime());
        seckillVoucherMapper.insert(seckill);
        return String.valueOf(voucher.getId());
    }

    @Override
    @Transactional
    public void update(SeckillVoucherDTO dto) {
        if (dto.getId() == null) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "更新操作缺少 id");
        }
        requireTimeOrder(dto);
        requireSeckill(dto.getId());

        Voucher voucherUpdate = new Voucher();
        voucherUpdate.setId(dto.getId());
        voucherUpdate.setShopId(dto.getShopId());
        voucherUpdate.setTitle(dto.getTitle());
        voucherUpdate.setSubTitle(dto.getSubTitle());
        voucherUpdate.setRules(dto.getRules());
        voucherUpdate.setPayValue(dto.getPayValue());
        voucherUpdate.setActualValue(dto.getActualValue());
        voucherUpdate.setStatus(dto.getStatus());
        voucherMapper.updateById(voucherUpdate);

        SeckillVoucher seckill = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, dto.getId()));
        seckill.setStock(dto.getStock());
        seckill.setMinLevel(dto.getMinLevel());
        seckill.setBeginTime(dto.getBeginTime());
        seckill.setEndTime(dto.getEndTime());
        seckillVoucherMapper.updateById(seckill);
    }

    @Override
    @Transactional
    public void delete(Long voucherId) {
        requireSeckill(voucherId);
        seckillVoucherMapper.delete(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
        voucherMapper.deleteById(voucherId);
    }

    @Override
    public SeckillVoucherVO detail(Long voucherId) {
        Voucher voucher = requireSeckill(voucherId);
        SeckillVoucher seckill = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId));
        return toVO(voucher, seckill);
    }

    @Override
    public List<SeckillVoucherVO> listByShop(Long shopId) {
        List<Voucher> vouchers = voucherMapper.selectList(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getShopId, shopId)
                .eq(Voucher::getType, TYPE_SECKILL)
                .eq(Voucher::getStatus, STATUS_ON_SHELF)
                .orderByAsc(Voucher::getId));
        if (vouchers.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> voucherIds = vouchers.stream().map(Voucher::getId).toList();
        Map<Long, SeckillVoucher> seckillMap = seckillVoucherMapper.selectList(
                        new LambdaQueryWrapper<SeckillVoucher>().in(SeckillVoucher::getVoucherId, voucherIds))
                .stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, Function.identity()));
        return vouchers.stream()
                .map(voucher -> toVO(voucher, seckillMap.get(voucher.getId())))
                .toList();
    }

    private void requireTimeOrder(SeckillVoucherDTO dto) {
        if (dto.getBeginTime() != null && dto.getEndTime() != null
                && !dto.getEndTime().isAfter(dto.getBeginTime())) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "结束时间必须晚于开抢时间");
        }
    }

    private Voucher requireSeckill(Long voucherId) {
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "秒杀券不存在");
        }
        if (voucher.getType() == null || voucher.getType() != TYPE_SECKILL) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "目标券不是秒杀券");
        }
        return voucher;
    }

    private SeckillVoucherVO toVO(Voucher voucher, SeckillVoucher seckill) {
        SeckillVoucherVO vo = new SeckillVoucherVO();
        vo.setVoucherId(voucher.getId());
        vo.setShopId(voucher.getShopId());
        vo.setTitle(voucher.getTitle());
        vo.setSubTitle(voucher.getSubTitle());
        vo.setRules(voucher.getRules());
        vo.setPayValue(voucher.getPayValue());
        vo.setActualValue(voucher.getActualValue());
        vo.setStatus(voucher.getStatus());
        if (seckill != null) {
            vo.setStock(seckill.getStock());
            vo.setMinLevel(seckill.getMinLevel());
            vo.setBeginTime(seckill.getBeginTime());
            vo.setEndTime(seckill.getEndTime());
        }
        return vo;
    }
}
