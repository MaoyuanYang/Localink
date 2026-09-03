package com.localink.cache;

import com.localink.cache.config.CacheProperties;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 布隆过滤器注册表：按 localink.cache.bloom.filters 配置初始化 Redisson RBloomFilter，
 * 业务方以别名读写，key 经 {@link KeyBuilder} 统一加前缀。
 */
public class BloomFilterRegistry {

    private final Map<String, RBloomFilter<String>> filters = new HashMap<>();

    public BloomFilterRegistry(RedissonClient redissonClient, KeyBuilder keyBuilder, CacheProperties cacheProperties) {
        Map<String, CacheProperties.Filter> configured = cacheProperties.getBloom().getFilters();
        for (Map.Entry<String, CacheProperties.Filter> entry : configured.entrySet()) {
            String alias = entry.getKey();
            CacheProperties.Filter filter = entry.getValue();
            if (filter.getKeyTemplate() == null || filter.getKeyTemplate().isBlank()) {
                throw new LocalinkException(BaseCode.SYSTEM_ERROR, "布隆过滤器缺少 key-template 配置: " + alias);
            }
            KeyBuild key = keyBuilder.build(() -> filter.getKeyTemplate());
            RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(key.getKey());
            if (!bloomFilter.tryInit(filter.getExpectedInsertions(), filter.getFalseProbability())) {
                throw new LocalinkException(BaseCode.SYSTEM_ERROR,
                        "布隆过滤器初始化失败（Redis 中已存在同名且参数不一致的实例，需先删除或对齐参数）: " + key.getKey());
            }
            filters.put(alias, bloomFilter);
        }
    }

    /**
     * 写入一个元素（已存在时无副作用）。
     */
    public boolean add(String alias, String value) {
        return resolve(alias).add(value);
    }

    /**
     * 判断元素是否可能存在：false 一定不存在，true 可能存在（有误判率）。
     */
    public boolean contains(String alias, String value) {
        return resolve(alias).contains(value);
    }

    private RBloomFilter<String> resolve(String alias) {
        RBloomFilter<String> bloomFilter = filters.get(alias);
        if (bloomFilter == null) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "未注册的布隆过滤器: " + alias);
        }
        return bloomFilter;
    }
}
