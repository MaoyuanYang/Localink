package com.localink.shop;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Shop;
import com.localink.framework.cache.LogicalExpiryEntry;
import com.localink.mapper.ShopMapper;
import com.localink.service.ShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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

    private static final Type ENTRY_TYPE = new TypeReference<LogicalExpiryEntry<ShopVO>>() {}.getType();

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

    private LogicalExpiryEntry<ShopVO> readEntry(Long id) {
        return JSON.parseObject(redisCache.strings().getString(shopKey(id)), ENTRY_TYPE);
    }

    private void expireEntryNow(Long id) {
        LogicalExpiryEntry<ShopVO> entry = new LogicalExpiryEntry<>();
        ShopVO stale = new ShopVO();
        stale.setId(id);
        stale.setName("stale-" + System.nanoTime());
        entry.setData(stale);
        entry.setExpireTime(LocalDateTime.now().minusHours(1));
        redisCache.strings().set(shopKey(id), entry);
    }

    private void awaitEntryName(Long id, String expectedName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            LogicalExpiryEntry<ShopVO> entry = readEntry(id);
            if (entry != null && entry.getData() != null && expectedName.equals(entry.getData().getName())) {
                return;
            }
            Thread.sleep(100);
        }
        LogicalExpiryEntry<ShopVO> entry = readEntry(id);
        fail("等待缓存重建超时, 期望 name=" + expectedName + ", 实际=" + (entry == null || entry.getData() == null ? "null" : entry.getData().getName()));
    }

    @Test
    void detailMissFillsCacheWithLogicalExpiry() {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        assertFalse(redisCache.hasKey(shopKey(id)));

        LocalDateTime before = LocalDateTime.now();
        ShopVO vo = shopService.detail(id);

        assertTrue(redisCache.hasKey(shopKey(id)));
        assertEquals(-1L, redisCache.getExpire(shopKey(id)));
        LogicalExpiryEntry<ShopVO> entry = readEntry(id);
        assertNotNull(entry);
        assertEquals(vo.getName(), entry.getData().getName());
        assertTrue(!entry.getExpireTime().isBefore(before.plusSeconds(1795)));
        assertTrue(entry.getExpireTime().isBefore(LocalDateTime.now().plusSeconds(2400)));
    }

    @Test
    void batchBackfillLogicalExpiriesAreJittered() {
        Set<LocalDateTime> expiries = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            Long id = Long.valueOf(shopService.create(newDto()));
            createdIds.add(id);
            shopService.detail(id);
            assertEquals(-1L, redisCache.getExpire(shopKey(id)));
            expiries.add(readEntry(id).getExpireTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        }
        assertTrue(expiries.size() >= 2);
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
    void freshEntryServedDirectlyWithoutDbHit() {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        ShopVO first = shopService.detail(id);
        verify(shopMapper, times(1)).selectById(id);

        ShopVO second = shopService.detail(id);

        assertEquals(first.getName(), second.getName());
        verify(shopMapper, times(1)).selectById(id);
    }

    @Test
    void expiredEntryServesStaleAndRebuildsAsync() throws Exception {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        shopService.detail(id);
        String freshName = "rebuild-after-" + System.nanoTime();
        Shop direct = new Shop();
        direct.setId(id);
        direct.setName(freshName);
        shopMapper.updateById(direct);
        expireEntryNow(id);

        ShopVO served = shopService.detail(id);

        assertNotEquals(freshName, served.getName());
        awaitEntryName(id, freshName);
        assertEquals(freshName, shopService.detail(id).getName());
        verify(shopMapper, times(2)).selectById(id);
    }

    @Test
    void concurrentExpiredKeyTriggersSingleRebuild() throws Exception {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        shopService.detail(id);
        String freshName = "concurrent-rebuild-" + System.nanoTime();
        Shop direct = new Shop();
        direct.setId(id);
        direct.setName(freshName);
        shopMapper.updateById(direct);
        expireEntryNow(id);
        String staleName = readEntry(id).getData().getName();

        int threads = 16;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> names = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    names.add(shopService.detail(id).getName());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发 detail 应在超时前全部返回");
        pool.shutdown();

        assertTrue(errors.isEmpty(), () -> "并发 detail 不应报错: " + errors);
        assertEquals(threads, names.size());
        names.forEach(name -> assertTrue(name.equals(staleName) || name.equals(freshName)));
        assertTrue(names.contains(staleName), "应有请求拿到旧值兜底");
        awaitEntryName(id, freshName);
        verify(shopMapper, times(2)).selectById(id);
        assertFalse(redisCache.hasKey(keyBuilder.build(KeyManage.SHOP_REBUILD_LOCK, id)), "异步重建完成后锁应已释放");
    }

    @Test
    void concurrentMissHitsDbExactlyOnce() throws Exception {
        Long id = Long.valueOf(shopService.create(newDto()));
        createdIds.add(id);
        assertFalse(redisCache.hasKey(shopKey(id)));

        int threads = 16;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<ShopVO> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(shopService.detail(id));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发 detail 应在超时前全部返回");
        pool.shutdown();

        assertTrue(errors.isEmpty(), () -> "并发 detail 不应报错: " + errors);
        assertEquals(threads, results.size());
        String expectedName = results.get(0).getName();
        results.forEach(vo -> assertEquals(expectedName, vo.getName()));
        verify(shopMapper, times(1)).selectById(id);
        assertFalse(redisCache.hasKey(keyBuilder.build(KeyManage.SHOP_REBUILD_LOCK, id)), "重建完成后锁应已释放");
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
