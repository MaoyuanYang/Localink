package com.localink.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopNearbyVO;
import com.localink.api.vo.ShopVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.BloomFilterRegistry;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.BloomFilterAlias;
import com.localink.constant.LocalCacheAlias;
import com.localink.constant.KeyManage;
import com.localink.entity.Shop;
import com.localink.framework.cache.LogicalExpiryEntry;
import com.localink.cache.LocalCache;
import com.localink.lock.DistributedLock;
import com.localink.lock.LockType;
import com.localink.mq.CacheInvalidationMessage;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.mapper.ShopMapper;
import com.localink.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.List;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    private static final Duration SHOP_LOGICAL_TTL = Duration.ofMinutes(30);
    private static final Duration SHOP_LOGICAL_TTL_JITTER = Duration.ofMinutes(10);
    private static final Duration SHOP_NULL_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration SHOP_NULL_TTL_JITTER = Duration.ofSeconds(30);
    private static final Duration SHOP_REBUILD_LOCK_WAIT = Duration.ofSeconds(3);
    private static final Type SHOP_ENTRY_TYPE = new TypeReference<LogicalExpiryEntry<ShopVO>>() {}.getType();

    private final ShopMapper shopMapper;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final ThreadPoolTaskExecutor cacheRebuildExecutor;
    private final BloomFilterRegistry bloomFilterRegistry;
    private final LocalCache<String, ShopVO> shopLocalCache;
    private final DistributedLock distributedLock;
    private final MessageProducer messageProducer;

    @Override
    public ShopVO detail(Long id) {
        ShopVO local = shopLocalCache.getIfPresent(localKey(id));
        if (local != null) {
            return local;
        }
        if (!bloomFilterRegistry.contains(BloomFilterAlias.SHOP, String.valueOf(id))) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户不存在");
        }
        KeyBuild key = shopKey(id);
        String raw = redisCache.strings().getString(key);
        if (raw == null) {
            return rebuildWithMutex(id, key);
        }
        if (raw.isEmpty()) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户不存在");
        }
        LogicalExpiryEntry<ShopVO> entry = parseEntry(raw);
        if (entry == null || entry.getData() == null || entry.getExpireTime() == null) {
            return rebuildWithMutex(id, key);
        }
        if (entry.getExpireTime().isAfter(LocalDateTime.now())) {
            shopLocalCache.put(localKey(id), entry.getData());
            return entry.getData();
        }
        triggerAsyncRebuild(id, key);
        return entry.getData();
    }

    private LogicalExpiryEntry<ShopVO> parseEntry(String raw) {
        try {
            return JSON.parseObject(raw, SHOP_ENTRY_TYPE);
        } catch (JSONException e) {
            return null;
        }
    }

    private void triggerAsyncRebuild(Long id, KeyBuild key) {
        cacheRebuildExecutor.execute(() -> {
            try {
                if (isEntryFresh(key)) {
                    return;
                }
                distributedLock.tryWithLock(rebuildLockKey(id), LockType.REENTRANT, null, () -> {
                    if (isEntryFresh(key)) {
                        return null;
                    }
                    loadAndCacheLogical(id, key);
                    return null;
                });
            } catch (Exception e) {
                log.error("商户缓存异步重建失败, shopId={}", id, e);
            }
        });
    }

    private boolean isEntryFresh(KeyBuild key) {
        LogicalExpiryEntry<ShopVO> current = parseEntry(redisCache.strings().getString(key));
        return current != null && current.getExpireTime() != null
                && current.getExpireTime().isAfter(LocalDateTime.now());
    }

    private ShopVO rebuildWithMutex(Long id, KeyBuild key) {
        ShopVO cached = resolveFilled(redisCache.strings().getString(key));
        if (cached != null) {
            return cached;
        }
        return distributedLock.runWithLock(rebuildLockKey(id), LockType.REENTRANT, SHOP_REBUILD_LOCK_WAIT, null, () -> {
            ShopVO rechecked = resolveFilled(redisCache.strings().getString(key));
            return rechecked != null ? rechecked : loadAndCacheLogical(id, key);
        });
    }

    private String rebuildLockKey(Long id) {
        return keyBuilder.build(KeyManage.SHOP_REBUILD_LOCK, id).getKey();
    }

    private ShopVO resolveFilled(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户不存在");
        }
        LogicalExpiryEntry<ShopVO> entry = parseEntry(raw);
        if (entry == null || entry.getData() == null) {
            return null;
        }
        return entry.getData();
    }

    private ShopVO loadAndCacheLogical(Long id, KeyBuild key) {
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            redisCache.strings().set(key, "", jittered(SHOP_NULL_CACHE_TTL, SHOP_NULL_TTL_JITTER));
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户不存在");
        }
        ShopVO vo = toVO(shop);
        redisCache.strings().set(key, entryOf(vo));
        shopLocalCache.put(localKey(id), vo);
        return vo;
    }

    private LogicalExpiryEntry<ShopVO> entryOf(ShopVO vo) {
        LogicalExpiryEntry<ShopVO> entry = new LogicalExpiryEntry<>();
        entry.setData(vo);
        entry.setExpireTime(LocalDateTime.now().plus(jittered(SHOP_LOGICAL_TTL, SHOP_LOGICAL_TTL_JITTER)));
        return entry;
    }

    @Override
    public Page<ShopVO> page(Long typeId, long page, long size) {
        // 上限钳制：防 size=999999 一把拉全表
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, size), 50);
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<Shop>()
                .eq(typeId != null, Shop::getTypeId, typeId)
                .orderByAsc(Shop::getId);
        Page<Shop> result = shopMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        Page<ShopVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /**
     * 布隆先入、DB 后写：布隆假阳性无害（空值缓存吸收），Redis 抖动时顶多留下一个多余 id；
     * 反序（先插库后入布隆）时 Redis 抖动会让已建商户被布隆永久误拦（重启才恢复）。
     * 事务包裹：GEO 写失败回滚 DB，脏坐标行无从落库（启动灌入不再被单行拖垮）。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public String create(ShopDTO dto) {
        Shop shop = new Shop();
        BeanUtils.copyProperties(dto, shop, "id");
        if (shop.getImages() == null) {
            shop.setImages("");
        }
        shop.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        bloomFilterRegistry.add(BloomFilterAlias.SHOP, String.valueOf(shop.getId()));
        shopMapper.insert(shop);
        addToGeo(shop);
        return String.valueOf(shop.getId());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void update(ShopDTO dto) {
        if (dto.getId() == null) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "更新操作缺少 id");
        }
        requireExists(dto.getId());
        Shop shop = new Shop();
        BeanUtils.copyProperties(dto, shop);
        shopMapper.updateById(shop);
        // GEO 覆盖写：member 已存在则坐标更新（GEOADD 幂等语义）
        addToGeo(shop);
        redisCache.delete(shopKey(dto.getId()));
        shopLocalCache.invalidate(localKey(dto.getId()));
        broadcastInvalidate(dto.getId());
        // 延迟双删：杀掉"在途重建任务查到旧值又写回"的竞争窗口（重建读 DB 与本提交交错的毫秒级缝隙）
        delayedDoubleDelete(dto.getId());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {
        requireExists(id);
        shopMapper.deleteById(id);
        redisCache.geos().remove(keyBuilder.build(KeyManage.SHOP_GEO), String.valueOf(id));
        redisCache.delete(shopKey(id));
        shopLocalCache.invalidate(localKey(id));
        broadcastInvalidate(id);
        delayedDoubleDelete(id);
    }

    /**
     * 1 秒后对 Redis 再删一次：不占用重建线程池（CompletableFuture 延时调度器执行）。
     */
    private void delayedDoubleDelete(Long id) {
        java.util.concurrent.CompletableFuture.runAsync(
                () -> redisCache.delete(shopKey(id)),
                java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Override
    public java.util.List<ShopNearbyVO> nearby(double longitude, double latitude,
                                               double radiusMeters, int count) {
        var entries = redisCache.geos().search(keyBuilder.build(KeyManage.SHOP_GEO),
                longitude, latitude, radiusMeters, count);
        if (entries.isEmpty()) {
            return List.of();
        }
        // 批量回填防 N+1（顺序按 GEO 距离升序保持）
        var shops = shopMapper.selectBatchIds(
                entries.stream().map(e -> Long.valueOf(e.member())).toList());
        var byId = new java.util.HashMap<Long, Shop>();
        shops.forEach(shop -> byId.put(shop.getId(), shop));
        return entries.stream().map(entry -> {
            Long id = Long.valueOf(entry.member());
            ShopNearbyVO vo = new ShopNearbyVO();
            vo.setId(id);
            vo.setDistance(entry.distanceMeters());
            Shop shop = byId.get(id);
            if (shop != null) {
                vo.setName(shop.getName());
                vo.setTypeId(shop.getTypeId());
                vo.setAddress(shop.getAddress());
            }
            return vo;
        }).toList();
    }

    private void addToGeo(Shop shop) {
        if (shop.getLongitude() != null && shop.getLatitude() != null) {
            // 范围防线（DTO 校验之后的第二道闸）：越界坐标会让 GEOADD 报错，绝不让它走到这
            if (shop.getLongitude() < -180.0 || shop.getLongitude() > 180.0
                    || shop.getLatitude() < -90.0 || shop.getLatitude() > 90.0) {
                throw new LocalinkException(BaseCode.PARAM_ERROR, "经纬度超出合法范围");
            }
            redisCache.geos().add(keyBuilder.build(KeyManage.SHOP_GEO),
                    shop.getLongitude(), shop.getLatitude(), String.valueOf(shop.getId()));
        }
    }

    private KeyBuild shopKey(Long id) {
        return keyBuilder.build(KeyManage.SHOP_INFO, id);
    }

    /**
     * 广播失效其他实例的本地缓存副本（本实例已同步失效，广播回来幂等无害）；
     * fire-and-forget——丢失由 L1 的 10s TTL 兜底（M2.10 设计）。
     */
    private void broadcastInvalidate(Long id) {
        messageProducer.sendAsync(MqTopics.CACHE_INVALIDATION, String.valueOf(id),
                new CacheInvalidationMessage(LocalCacheAlias.SHOP, localKey(id)));
    }

    private String localKey(Long id) {
        return String.valueOf(id);
    }

    private Duration jittered(Duration base, Duration jitter) {
        long seconds = ThreadLocalRandom.current().nextLong(jitter.toSeconds() + 1);
        return base.plusSeconds(seconds);
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
