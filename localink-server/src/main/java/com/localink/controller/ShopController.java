package com.localink.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;
import com.localink.common.result.Result;
import com.localink.service.ShopService;
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

@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    @GetMapping("/{id}")
    public Result<ShopVO> detail(@PathVariable Long id) {
        return Result.ok(shopService.detail(id));
    }

    @GetMapping("/page")
    public Result<Page<ShopVO>> page(@RequestParam(required = false) Long typeId,
                                     @RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "10") long size) {
        return Result.ok(shopService.page(typeId, page, size));
    }

    @PostMapping
    public Result<String> create(@Validated @RequestBody ShopDTO dto) {
        return Result.ok(shopService.create(dto));
    }

    @PutMapping
    public Result<Void> update(@Validated @RequestBody ShopDTO dto) {
        shopService.update(dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        shopService.delete(id);
        return Result.ok();
    }
}
