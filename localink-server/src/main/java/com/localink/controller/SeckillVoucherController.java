package com.localink.controller;

import com.localink.api.dto.SeckillVoucherDTO;
import com.localink.api.vo.SeckillVoucherVO;
import com.localink.common.result.Result;
import com.localink.service.SeckillVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/seckill-voucher")
@RequiredArgsConstructor
public class SeckillVoucherController {

    private final SeckillVoucherService seckillVoucherService;

    @PostMapping
    public Result<String> create(@Validated @RequestBody SeckillVoucherDTO dto) {
        return Result.ok(seckillVoucherService.create(dto));
    }

    @PutMapping
    public Result<Void> update(@Validated @RequestBody SeckillVoucherDTO dto) {
        seckillVoucherService.update(dto);
        return Result.ok();
    }

    @DeleteMapping("/{voucherId}")
    public Result<Void> delete(@PathVariable Long voucherId) {
        seckillVoucherService.delete(voucherId);
        return Result.ok();
    }

    @GetMapping("/{voucherId}")
    public Result<SeckillVoucherVO> detail(@PathVariable Long voucherId) {
        return Result.ok(seckillVoucherService.detail(voucherId));
    }

    @GetMapping("/list")
    public Result<List<SeckillVoucherVO>> list(@RequestParam Long shopId) {
        return Result.ok(seckillVoucherService.listByShop(shopId));
    }
}
