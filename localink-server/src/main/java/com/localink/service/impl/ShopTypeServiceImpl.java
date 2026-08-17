package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.ShopTypeDTO;
import com.localink.api.vo.ShopTypeVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.Shop;
import com.localink.entity.ShopType;
import com.localink.mapper.ShopMapper;
import com.localink.mapper.ShopTypeMapper;
import com.localink.service.ShopTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShopTypeServiceImpl implements ShopTypeService {

    private final ShopTypeMapper shopTypeMapper;
    private final ShopMapper shopMapper;

    @Override
    public List<ShopTypeVO> list() {
        List<ShopType> types = shopTypeMapper.selectList(
                new LambdaQueryWrapper<ShopType>().orderByAsc(ShopType::getSort));
        return types.stream().map(this::toVO).toList();
    }

    @Override
    public String create(ShopTypeDTO dto) {
        ShopType type = new ShopType();
        BeanUtils.copyProperties(dto, type, "id");
        if (type.getSort() == null) {
            type.setSort(0);
        }
        shopTypeMapper.insert(type);
        return String.valueOf(type.getId());
    }

    @Override
    public void update(ShopTypeDTO dto) {
        requireId(dto.getId());
        requireExists(dto.getId());
        ShopType type = new ShopType();
        BeanUtils.copyProperties(dto, type);
        shopTypeMapper.updateById(type);
    }

    @Override
    public void delete(Long id) {
        requireExists(id);
        Long shopCount = shopMapper.selectCount(new LambdaQueryWrapper<Shop>().eq(Shop::getTypeId, id));
        if (shopCount > 0) {
            throw new LocalinkException(BaseCode.SHOP_TYPE_IN_USE);
        }
        shopTypeMapper.deleteById(id);
    }

    private void requireId(Long id) {
        if (id == null) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "更新操作缺少 id");
        }
    }

    private void requireExists(Long id) {
        if (shopTypeMapper.selectById(id) == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户类型不存在");
        }
    }

    private ShopTypeVO toVO(ShopType type) {
        ShopTypeVO vo = new ShopTypeVO();
        BeanUtils.copyProperties(type, vo);
        return vo;
    }
}
