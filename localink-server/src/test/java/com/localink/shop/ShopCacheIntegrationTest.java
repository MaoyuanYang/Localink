package com.localink.shop;

import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Shop;
import com.localink.mapper.ShopMapper;
import com.localink.service.ShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ShopCacheIntegrationTest {

    @Autowired
    private ShopService shopService;

    @MockitoSpyBean
    private ShopMapper shopMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(id -> {
            shopMapper.deleteById(id);
            redisCache.delete(shopKey(id));
        });
    }

    private ShopDTO newDto() {
        ShopDTO dto = new ShopDTO();
        dto.setName("缓存测试商户-" + System.nanoTime());
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

    private KeyBuild shopKey(Long id) {
        return keyBuilder.build(KeyManage.SHOP_INFO, id);
    }

    @Test
    void detailMissFillsCacheWithTtl() {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        assertFalse(redisCache.hasKey(shopKey(id)));

        ShopVO vo = shopService.detail(id);

        assertTrue(redisCache.hasKey(shopKey(id)));
        Long expire = redisCache.getExpire(shopKey(id));
        assertTrue(expire >= 1795 && expire < 2400);
        ShopVO cached = redisCache.strings().get(shopKey(id), ShopVO.class);
        assertEquals(vo.getName(), cached.getName());
        assertEquals(vo.getId(), cached.getId());
    }

    @Test
    void batchBackfillTtlsAreJittered() {
        List<Long> expires = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long id = Long.valueOf(shopService.create(newDto()));
            createdIds.add(id);
            shopService.detail(id);
            expires.add(redisCache.getExpire(shopKey(id)));
        }
        expires.forEach(expire -> assertTrue(expire >= 1795 && expire < 2400));
        assertTrue(expires.stream().distinct().count() >= 2);
    }

    @Test
    void detailHitsCacheUntilUpdateInvalidatesIt() {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        String originalName = shopService.detail(id).getName();

        Shop directUpdate = new Shop();
        directUpdate.setId(id);
        directUpdate.setName(originalName + "-直改库");
        shopMapper.updateById(directUpdate);
        assertEquals(originalName, shopService.detail(id).getName());

        ShopDTO updateDto = newDto();
        updateDto.setId(id);
        updateDto.setName(originalName + "-service改");
        shopService.update(updateDto);

        assertFalse(redisCache.hasKey(shopKey(id)));
        assertEquals(updateDto.getName(), shopService.detail(id).getName());
    }

    @Test
    void detailOfMissingShopWritesEmptyMarkerWithShortTtl() {
        long missingId = System.nanoTime();

        LocalinkException ex = assertThrows(LocalinkException.class, () -> shopService.detail(missingId));

        assertEquals(BaseCode.NOT_FOUND.getCode(), ex.getCode());
        assertEquals("", redisCache.strings().getString(shopKey(missingId)));
        Long expire = redisCache.getExpire(shopKey(missingId));
        assertTrue(expire >= 115 && expire < 150);
        redisCache.delete(shopKey(missingId));
    }

    @Test
    void emptyMarkerAbsorbsRepeatedMissesWithoutDbHit() {
        long missingId = System.nanoTime();

        LocalinkException first = assertThrows(LocalinkException.class, () -> shopService.detail(missingId));
        LocalinkException second = assertThrows(LocalinkException.class, () -> shopService.detail(missingId));

        assertEquals(BaseCode.NOT_FOUND.getCode(), first.getCode());
        assertEquals(BaseCode.NOT_FOUND.getCode(), second.getCode());
        verify(shopMapper, times(1)).selectById(missingId);
        redisCache.delete(shopKey(missingId));
    }

    @Test
    void deleteRemovesShopAndCache() {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        shopService.detail(id);
        assertTrue(redisCache.hasKey(shopKey(id)));

        shopService.delete(id);
        createdIds.remove(id);

        assertNull(shopMapper.selectById(id));
        assertFalse(redisCache.hasKey(shopKey(id)));
        LocalinkException ex = assertThrows(LocalinkException.class, () -> shopService.detail(id));
        assertEquals(BaseCode.NOT_FOUND.getCode(), ex.getCode());
    }
}
