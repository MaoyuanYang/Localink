package com.localink.controller;

import com.localink.api.vo.VoucherOrderVO;
import com.localink.common.result.Result;
import com.localink.framework.auth.AdminOnly;
import com.localink.service.VoucherOrderService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的订单（W1）：GET 匿名可达（LoginInterceptor 只拦非 GET 与 /api/user/**），
 * 登录收口在 Service 首行（UserHolder==null → 40002），只查本人订单、无越界面。
 * admin 两端点（W2）：按活动查订单 + 手动关单——手动关单定位为运营补偿/演示入口，
 * 正路仍是 15 分钟延迟队列（页面双轨文案）。
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final VoucherOrderService voucherOrderService;

    @GetMapping("/page")
    public Result<Page<VoucherOrderVO>> page(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return Result.ok(voucherOrderService.pageMyOrders(page, size));
    }

    @AdminOnly
    @GetMapping("/admin/page")
    public Result<Page<VoucherOrderVO>> adminPage(@RequestParam Long voucherId,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size) {
        return Result.ok(voucherOrderService.pageOrdersByVoucher(voucherId, page, size));
    }

    @AdminOnly
    @PostMapping("/admin/{orderId}/close")
    public Result<Boolean> adminClose(@PathVariable Long orderId) {
        return Result.ok(voucherOrderService.closeOrderIfExpired(orderId));
    }
}
