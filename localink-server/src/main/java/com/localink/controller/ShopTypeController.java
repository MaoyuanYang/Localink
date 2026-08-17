package com.localink.controller;

import com.localink.api.dto.ShopTypeDTO;
import com.localink.api.vo.ShopTypeVO;
import com.localink.common.result.Result;
import com.localink.service.ShopTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shop-type")
@RequiredArgsConstructor
public class ShopTypeController {

    private final ShopTypeService shopTypeService;

    @GetMapping("/list")
    public Result<List<ShopTypeVO>> list() {
        return Result.ok(shopTypeService.list());
    }

    @PostMapping
    public Result<String> create(@Validated @RequestBody ShopTypeDTO dto) {
        return Result.ok(shopTypeService.create(dto));
    }

    @PutMapping
    public Result<Void> update(@Validated @RequestBody ShopTypeDTO dto) {
        shopTypeService.update(dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        shopTypeService.delete(id);
        return Result.ok();
    }
}
