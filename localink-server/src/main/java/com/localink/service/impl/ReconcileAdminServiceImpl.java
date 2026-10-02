package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.vo.PageVO;
import com.localink.api.vo.ReconcileLogVO;
import com.localink.api.vo.RollbackFailureVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.RollbackFailureLog;
import com.localink.entity.VoucherReconcileLog;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.RollbackFailureLogMapper;
import com.localink.mapper.VoucherReconcileLogMapper;
import com.localink.service.ReconcileAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReconcileAdminServiceImpl implements ReconcileAdminService {

    private final VoucherReconcileLogMapper reconcileLogMapper;
    private final RollbackFailureLogMapper rollbackFailureLogMapper;

    @Override
    public PageVO<ReconcileLogVO> pageLogs(long page, long size, Integer reconciliationStatus) {
        requireLogin();
        Page<VoucherReconcileLog> raw = reconcileLogMapper.selectPage(safe(page, size),
                new LambdaQueryWrapper<VoucherReconcileLog>()
                        .eq(reconciliationStatus != null, VoucherReconcileLog::getReconciliationStatus, reconciliationStatus)
                        .orderByDesc(VoucherReconcileLog::getId));
        List<ReconcileLogVO> records = raw.getRecords().stream().map(this::toLogVo).toList();
        return PageVO.of(raw.getTotal(), records);
    }

    @Override
    public PageVO<RollbackFailureVO> pageFailures(long page, long size) {
        requireLogin();
        Page<RollbackFailureLog> raw = rollbackFailureLogMapper.selectPage(safe(page, size),
                new LambdaQueryWrapper<RollbackFailureLog>()
                        .orderByDesc(RollbackFailureLog::getId));
        List<RollbackFailureVO> records = raw.getRecords().stream().map(this::toFailureVo).toList();
        return PageVO.of(raw.getTotal(), records);
    }

    private void requireLogin() {
        if (UserHolder.get() == null) {
            throw new LocalinkException(BaseCode.UNAUTHORIZED);
        }
    }

    private <T> Page<T> safe(long page, long size) {
        return new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 50));
    }

    private ReconcileLogVO toLogVo(VoucherReconcileLog e) {
        ReconcileLogVO vo = new ReconcileLogVO();
        vo.setId(e.getId());
        vo.setOrderId(e.getOrderId());
        vo.setUserId(e.getUserId());
        vo.setVoucherId(e.getVoucherId());
        vo.setTraceId(e.getTraceId());
        vo.setLogType(e.getLogType());
        vo.setBusinessType(e.getBusinessType());
        vo.setReconciliationStatus(e.getReconciliationStatus());
        vo.setBeforeQty(e.getBeforeQty());
        vo.setChangeQty(e.getChangeQty());
        vo.setAfterQty(e.getAfterQty());
        vo.setDetail(e.getDetail());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }

    private RollbackFailureVO toFailureVo(RollbackFailureLog e) {
        RollbackFailureVO vo = new RollbackFailureVO();
        vo.setId(e.getId());
        vo.setVoucherId(e.getVoucherId());
        vo.setUserId(e.getUserId());
        vo.setOrderId(e.getOrderId());
        vo.setTraceId(e.getTraceId());
        vo.setResultCode(e.getResultCode() == null ? null : String.valueOf(e.getResultCode()));
        vo.setRetryAttempts(e.getRetryAttempts());
        vo.setSource(e.getSource());
        vo.setDetail(e.getDetail());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }
}
