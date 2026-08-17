package com.localink.shop;

import com.localink.api.dto.ShopTypeDTO;
import com.localink.api.vo.ShopTypeVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.ShopType;
import com.localink.mapper.ShopTypeMapper;
import com.localink.service.ShopTypeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ShopTypeCrudIntegrationTest {

    @Autowired
    private ShopTypeService shopTypeService;

    @Autowired
    private ShopTypeMapper shopTypeMapper;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(shopTypeMapper::deleteById);
    }

    @Test
    void listReturnsSeededTypesSortedBySort() {
        List<ShopTypeVO> types = shopTypeService.list();
        assertTrue(types.size() >= 10);
        for (int i = 1; i < types.size(); i++) {
            assertTrue(types.get(i - 1).getSort() <= types.get(i).getSort());
        }
    }

    @Test
    void createUpdateDeleteLifecycle() {
        ShopTypeDTO dto = new ShopTypeDTO();
        dto.setName("测试类型-" + System.nanoTime());
        dto.setIcon("/icons/test.png");
        dto.setSort(99);

        Long id = Long.valueOf(shopTypeService.create(dto));
        createdIds.add(id);

        dto.setId(id);
        dto.setName(dto.getName() + "-改");
        shopTypeService.update(dto);
        ShopType updated = shopTypeMapper.selectById(id);
        assertEquals(dto.getName(), updated.getName());

        shopTypeService.delete(id);
        createdIds.remove(id);
        assertNull(shopTypeMapper.selectById(id));
    }

    @Test
    void deleteTypeInUseRejected() {
        LocalinkException ex = assertThrows(LocalinkException.class, () -> shopTypeService.delete(1L));
        assertEquals(BaseCode.SHOP_TYPE_IN_USE.getCode(), ex.getCode());
    }

    @Test
    void updateWithoutIdRejected() {
        ShopTypeDTO dto = new ShopTypeDTO();
        dto.setName("缺少id的类型");
        LocalinkException ex = assertThrows(LocalinkException.class, () -> shopTypeService.update(dto));
        assertEquals(BaseCode.PARAM_ERROR.getCode(), ex.getCode());
    }
}
