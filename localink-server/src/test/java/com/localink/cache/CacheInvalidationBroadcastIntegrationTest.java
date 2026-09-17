package com.localink.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.ShopDTO;
import com.localink.constant.LocalCacheAlias;
import com.localink.entity.Shop;
import com.localink.mapper.ShopMapper;
import com.localink.mq.CacheInvalidationMessage;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.service.ShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * M3.9 失效广播：独立消费组（random.uuid）必收全量消息——本上下文的组收到手动发送的广播并踢 L1；
 * update/delete 后广播确实发出（spy 生产者）。踢缓存幂等，重复消息无害。
 */
@SpringBootTest
class CacheInvalidationBroadcastIntegrationTest {

    @Autowired
    private ShopService shopService;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private LocalCache<String, com.localink.api.vo.ShopVO> shopLocalCache;

    @Autowired
    private MessageProducer messageProducer;

    @SpyBean
    private MessageProducer messageProducerSpy;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdIds.forEach(id -> {
            shopMapper.deleteById(id);
            shopLocalCache.invalidate(String.valueOf(id));
        });
    }

    @Test
    void broadcastMessageInvalidatesLocalCacheAcrossInstances() throws Exception {
        Long id = createShop();
        shopService.detail(id);
        shopService.detail(id);
        assertNotNull(shopLocalCache.getIfPresent(String.valueOf(id)), "预热：L1 应已回填");

        messageProducer.sendAsync(MqTopics.CACHE_INVALIDATION, String.valueOf(id),
                new CacheInvalidationMessage(LocalCacheAlias.SHOP, String.valueOf(id)));

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && shopLocalCache.getIfPresent(String.valueOf(id)) != null) {
            Thread.sleep(100);
        }
        assertNull(shopLocalCache.getIfPresent(String.valueOf(id)), "广播消费后本实例 L1 应被踢出");
    }

    @Test
    void unknownCacheAliasIsSkippedWithoutPoisonLoop() throws Exception {
        Long id = createShop();
        shopService.detail(id);
        shopService.detail(id);
        assertNotNull(shopLocalCache.getIfPresent(String.valueOf(id)));

        messageProducer.sendAsync(MqTopics.CACHE_INVALIDATION, String.valueOf(id),
                new CacheInvalidationMessage("no-such-cache", String.valueOf(id)));

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertNotNull(shopLocalCache.getIfPresent(String.valueOf(id)), "未知别名跳过不应影响其他缓存");
    }

    @Test
    void updateAndDeleteBroadcastInvalidation() {
        Long id = createShop();
        shopService.detail(id);

        ShopDTO dto = new ShopDTO();
        dto.setId(id);
        dto.setName("m3.9-updated");
        shopService.update(dto);

        verify(messageProducerSpy).sendAsync(eq(MqTopics.CACHE_INVALIDATION), eq(String.valueOf(id)),
                argThat(msg -> msg instanceof CacheInvalidationMessage message
                        && LocalCacheAlias.SHOP.equals(message.cache())
                        && String.valueOf(id).equals(message.key())));

        shopService.delete(id);
        verify(messageProducerSpy, times(2)).sendAsync(eq(MqTopics.CACHE_INVALIDATION),
                eq(String.valueOf(id)), ArgumentMatchers.any());
    }

    private Long createShop() {
        ShopDTO dto = new ShopDTO();
        dto.setTypeId(10L);
        dto.setName("m3.9-broadcast-" + System.nanoTime());
        dto.setImages("/images/test-1.jpg");
        dto.setArea("测试商圈");
        dto.setAddress("测试地址1号");
        dto.setLongitude(120.123456);
        dto.setLatitude(30.123456);
        dto.setAvgPrice(9900L);
        dto.setOpenHours("09:00-21:00");
        Long id = Long.valueOf(shopService.create(dto));
        createdIds.add(id);
        return id;
    }
}
