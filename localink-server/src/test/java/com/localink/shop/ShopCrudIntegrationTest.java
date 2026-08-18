package com.localink.shop;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.mapper.ShopMapper;
import com.localink.service.ShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ShopCrudIntegrationTest {

    @Autowired
    private ShopService shopService;

    @Autowired
    private ShopMapper shopMapper;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(shopMapper::deleteById);
    }

    private ShopDTO newDto() {
        ShopDTO dto = new ShopDTO();
        dto.setName("测试商户-" + System.nanoTime());
        dto.setTypeId(10L);
        dto.setImages("/images/test-1.jpg");
        dto.setArea("测试商圈");
        dto.setAddress("测试地址1号");
        dto.setLongitude(120.123456);
        dto.setLatitude(30.123456);
        dto.setAvgPrice(9900L);
        dto.setOpenHours("09:00-21:00");
        return dto;
    }

    @Test
    void fullCrudLifecycle() {
        ShopDTO dto = newDto();
        Long id = Long.valueOf(shopService.create(dto));
        createdIds.add(id);

        ShopVO vo = shopService.detail(id);
        assertEquals(dto.getName(), vo.getName());
        assertEquals(dto.getTypeId(), vo.getTypeId());
        assertEquals(0, vo.getSold());

        dto.setId(id);
        dto.setName(dto.getName() + "-改");
        shopService.update(dto);
        assertEquals(dto.getName(), shopService.detail(id).getName());

        shopService.delete(id);
        createdIds.remove(id);
        LocalinkException ex = assertThrows(LocalinkException.class, () -> shopService.detail(id));
        assertEquals(BaseCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void pageFiltersByTypeAndRespectsSize() {
        ShopDTO dto = newDto();
        Long id = Long.valueOf(shopService.create(dto));
        createdIds.add(id);

        Page<ShopVO> filtered = shopService.page(10L, 1, 50);
        assertTrue(filtered.getTotal() >= 2);
        assertTrue(filtered.getRecords().stream().anyMatch(v -> v.getId().equals(id)));

        Page<ShopVO> all = shopService.page(null, 1, 5);
        assertEquals(5, all.getRecords().size());
        assertTrue(all.getTotal() >= 10);
    }
}
