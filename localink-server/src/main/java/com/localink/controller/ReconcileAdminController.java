package com.localink.controller;

import com.localink.api.vo.PageVO;
import com.localink.api.vo.ReconcileLogVO;
import com.localink.api.vo.RollbackFailureVO;
import com.localink.common.result.Result;
import com.localink.framework.auth.AdminOnly;
import com.localink.service.ReconcileAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账运营看板（W3）：Redis 流水↔DB 比对账本与回滚失败表，只读观测。
 */
@RestController
@RequestMapping("/api/reconcile/admin")
@RequiredArgsConstructor
public class ReconcileAdminController {

    private final ReconcileAdminService reconcileAdminService;

    @AdminOnly
    @GetMapping("/page")
    public Result<PageVO<ReconcileLogVO>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) Integer status) {
        return Result.ok(reconcileAdminService.pageLogs(page, size, status));
    }

    @AdminOnly
    @GetMapping("/failures")
    public Result<PageVO<RollbackFailureVO>> failures(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size) {
        return Result.ok(reconcileAdminService.pageFailures(page, size));
    }
}
