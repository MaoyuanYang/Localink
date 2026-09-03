package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.ShopDTO;
import com.localink.api.vo.ShopVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.json.RedisJsonCodec;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Shop;
import com.localink.mapper.ShopMapper;
import com.localink.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    private static final Duration SHOP_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration SHOP_CACHE_TTL_JITTER = Duration.ofMinutes(10);
    private static final Duration SHOP_NULL_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration SHOP_NULL_TTL_JITTER = Duration.ofSeconds(30);
    private static final Duration SHOP_REBUILD_LOCK_TTL = Duration.ofSeconds(10);
    private static final int REBUILD_RETRY_LIMIT = 50;
    private static final long REBUILD_RETRY_INTERVAL_MS = 50;

    private final ShopMapper shopMapper;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    public ShopVO detail(Long id) {
        KeyBuild key = shopKey(id);
        String raw = redisCache.strings().getString(key);
        if (raw != null) {
            return resolveCached(raw);
        }
        return rebuildWithMutex(id, key);
    }

    private ShopVO resolveCached(String raw) {
        if (raw.isEmpty()) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户不存在");
        }
        return RedisJsonCodec.deserialize(raw, ShopVO.class);
    }

    private ShopVO rebuildWithMutex(Long id, KeyBuild key) {
        KeyBuild lockKey = keyBuilder.build(KeyManage.SHOP_REBUILD_LOCK, id);
        for (int attempt = 0; attempt < REBUILD_RETRY_LIMIT; attempt++) {
            String raw = redisCache.strings().getString(key);
            if (raw != null) {
                return resolveCached(raw);
            }
            if (redisCache.strings().setIfAbsent(lockKey, "1", SHOP_REBUILD_LOCK_TTL)) {
                try {
                    raw = redisCache.strings().getString(key);
                    if (raw != null) {
                        return resolveCached(raw);
                    }
                    return loadAndCache(id, key);
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

    private ShopVO loadAndCache(Long id, KeyBuild key) {
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            redisCache.strings().set(key, "", jittered(SHOP_NULL_CACHE_TTL, SHOP_NULL_TTL_JITTER));
            throw new LocalinkException(BaseCode.NOT_FOUND, "商户不存在");
        }
        ShopVO vo = toVO(shop);
        redisCache.strings().set(key, vo, jittered(SHOP_CACHE_TTL, SHOP_CACHE_TTL_JITTER));
        return vo;
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
