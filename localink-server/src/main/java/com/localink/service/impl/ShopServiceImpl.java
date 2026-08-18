package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.Shop;
import com.localink.mapper.ShopMapper;
import com.localink.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    private final ShopMapper shopMapper;

    @Override
    public ShopVO detail(Long id) {
        return toVO(requireExists(id));
    }

    @Override
    public Page<ShopVO> page(Long typeId, long page, long size) {
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<Shop>()
                .eq(typeId != null, Shop::getTypeId, typeId)
                .orderByAsc(Shop::getId);
        Page<Shop> result = shopMapper.selectPage(new Page<>(page, size), wrapper);
        Page<ShopVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public String create(ShopDTO dto) {
        Shop shop = new Shop();
        BeanUtils.copyProperties(dto, shop, "id");
        shopMapper.insert(shop);
        return String.valueOf(shop.getId());
    }

    @Override
    public void update(ShopDTO dto) {
        if (dto.getId() == null) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "更新操作缺少 id");
        }
        requireExists(dto.getId());
        Shop shop = new Shop();
        BeanUtils.copyProperties(dto, shop);
        shopMapper.updateById(shop);
    }

    @Override
    public void delete(Long id) {
        requireExists(id);
        shopMapper.deleteById(id);
    }

    private Shop requireExists(Long id) {
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户不存在");
        }
        return shop;
    }

    private ShopVO toVO(Shop shop) {
        ShopVO vo = new ShopVO();
        BeanUtils.copyProperties(shop, vo);
        return vo;
    }
}
