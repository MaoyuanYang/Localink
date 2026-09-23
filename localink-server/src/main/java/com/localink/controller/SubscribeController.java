package com.localink.controller;

import com.localink.common.result.Result;
import com.localink.framework.holder.UserHolder;
import com.localink.service.SubscribeService;
import com.localink.service.TopBuyerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M5-C 通知与运营统计：秒杀券订阅（排队/取消/状态）与店铺每日 Top 买家。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SubscribeController {

    private final SubscribeService subscribeService;
    private final TopBuyerService topBuyerService;

    @PostMapping("/seckill-voucher/{voucherId}/subscribe")
    public Result<Void> subscribe(@PathVariable Long voucherId) {
        subscribeService.subscribe(voucherId, UserHolder.get().getId());
        return Result.ok();
    }

    @DeleteMapping("/seckill-voucher/{voucherId}/subscribe")
    public Result<Void> unsubscribe(@PathVariable Long voucherId) {
        subscribeService.unsubscribe(voucherId, UserHolder.get().getId());
        return Result.ok();
    }

    @GetMapping("/seckill-voucher/{voucherId}/subscribe")
    public Result<String> subscribeStatus(@PathVariable Long voucherId) {
        return Result.ok(subscribeService.status(voucherId, UserHolder.get().getId()));
    }

    @GetMapping("/shop/{shopId}/top-buyers")
    public Result<List<Map<String, Object>>> topBuyers(@PathVariable Long shopId,
                                                       @RequestParam(required = false) String date) {
        return Result.ok(topBuyerService.topBuyers(shopId, date, 10));
    }
}
