package com.localink.shop;

import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.LocalCache;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 本地缓存 TTL 过期回源集成测试：独立上下文，shop 本地缓存 expire-after-write 压到 1s。
 */
@SpringBootTest(properties = "localink.cache.local.caches.shop.expire-after-write=1s")
class ShopLocalCacheExpiryIntegrationTest {

    @Autowired
    private ShopService shopService;

    @Autowired
    private ShopMapper shopMapper;

    @MockitoSpyBean
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private LocalCache<String, ShopVO> shopLocalCache;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(id -> {
            shopMapper.deleteById(id);
            redisCache.delete(shopKey(id));
            shopLocalCache.invalidate(String.valueOf(id));
        });
    }

    private ShopDTO newDto() {
        ShopDTO dto = new ShopDTO();
        dto.setName("L1过期测试商户-" + System.nanoTime());
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
    void localCacheExpiryFallsBackToRedis() throws InterruptedException {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        ShopVO first = shopService.detail(id);
        clearInvocations(redisCache);

        ShopVO hit = shopService.detail(id);
        assertEquals(first.getName(), hit.getName());
        verify(redisCache, never()).strings();

        Thread.sleep(1500);

        ShopVO after = shopService.detail(id);
        assertNotNull(after);
        assertEquals(first.getName(), after.getName());
        verify(redisCache, times(1)).strings();
        assertNotNull(shopLocalCache.getIfPresent(String.valueOf(id)), "回源 Redis 后应重新回填 L1");
    }
}
