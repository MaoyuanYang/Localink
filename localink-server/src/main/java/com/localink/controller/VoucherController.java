package com.localink.controller;

import com.localink.api.dto.VoucherDTO;
import com.localink.api.vo.VoucherVO;
import com.localink.common.result.Result;
import com.localink.service.VoucherService;
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
@RequestMapping("/api/voucher")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;

    @GetMapping("/list")
    public Result<List<VoucherVO>> list(@RequestParam Long shopId) {
        return Result.ok(voucherService.listByShop(shopId));
    }

    @GetMapping("/{id}")
    public Result<VoucherVO> detail(@PathVariable Long id) {
        return Result.ok(voucherService.detail(id));
    }

    @PostMapping
    public Result<String> create(@Validated @RequestBody VoucherDTO dto) {
        return Result.ok(voucherService.create(dto));
    }

    @PutMapping
    public Result<Void> update(@Validated @RequestBody VoucherDTO dto) {
        voucherService.update(dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        voucherService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/claim")
    public Result<String> claim(@PathVariable Long id) {
        return Result.ok(voucherService.claim(id));
    }
}
