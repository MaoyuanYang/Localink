package com.localink.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
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
    private static final Duration SHOP_REBUILD_LOCK_TTL = Duration.ofSeconds(10);
    private static final int REBUILD_RETRY_LIMIT = 50;
    private static final long REBUILD_RETRY_INTERVAL_MS = 50;
    private static final Type SHOP_ENTRY_TYPE = new TypeReference<LogicalExpiryEntry<ShopVO>>() {}.getType();

    private final ShopMapper shopMapper;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final ThreadPoolTaskExecutor cacheRebuildExecutor;

    @Override
    public ShopVO detail(Long id) {
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
        KeyBuild lockKey = keyBuilder.build(KeyManage.SHOP_REBUILD_LOCK, id);
        if (!redisCache.strings().setIfAbsent(lockKey, "1", SHOP_REBUILD_LOCK_TTL)) {
            return;
        }
        cacheRebuildExecutor.execute(() -> {
            try {
                LogicalExpiryEntry<ShopVO> current = parseEntry(redisCache.strings().getString(key));
                if (current != null && current.getExpireTime() != null
                        && current.getExpireTime().isAfter(LocalDateTime.now())) {
                    return;
                }
                loadAndCacheLogical(id, key);
            } catch (Exception e) {
                log.error("商户缓存异步重建失败, shopId={}", id, e);
            } finally {
                redisCache.delete(lockKey);
            }
        });
    }

    private ShopVO rebuildWithMutex(Long id, KeyBuild key) {
        KeyBuild lockKey = keyBuilder.build(KeyManage.SHOP_REBUILD_LOCK, id);
        for (int attempt = 0; attempt < REBUILD_RETRY_LIMIT; attempt++) {
            ShopVO cached = resolveFilled(redisCache.strings().getString(key));
            if (cached != null) {
                return cached;
            }
            if (redisCache.strings().setIfAbsent(lockKey, "1", SHOP_REBUILD_LOCK_TTL)) {
                try {
                    cached = resolveFilled(redisCache.strings().getString(key));
                    if (cached != null) {
                        return cached;
                    }
                    return loadAndCacheLogical(id, key);
                } finally {
                    redisCache.delete(lockKey);
                }
            }
            try {
                Thread.sleep(REBUILD_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LocalinkException(BaseCode.SYSTEM_ERROR, "缓存重建等待被中断");
            }
        }
        throw new LocalinkException(BaseCode.SYSTEM_ERROR, "缓存重建繁忙，请稍后再试");
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
        redisCache.delete(shopKey(dto.getId()));
    }

    @Override
    public void delete(Long id) {
        requireExists(id);
        shopMapper.deleteById(id);
        redisCache.delete(shopKey(id));
    }

    private KeyBuild shopKey(Long id) {
        return keyBuilder.build(KeyManage.SHOP_INFO, id);
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
