package com.localink.cache;

import java.time.Duration;
import java.util.Collection;

/**
 * Redis 缓存门面入口：key 通用操作 + 按数据结构分组的子门面。key 一律经 {@link KeyBuilder} 治理生成。
 * <p>序列化约定：key 一律 String；value 为 String 时原样写入，其余对象 JSON 化（fastjson2）。</p>
 */
public interface RedisCache {

    /**
     * 判断 key 是否存在。
     */
    boolean hasKey(KeyBuild key);

    /**
     * 删除单个 key。
     */
    void delete(KeyBuild key);

    /**
     * 批量删除 key。
     */
    void delete(Collection<KeyBuild> keys);

    /**
     * 设置 key 的过期时间。
     *
     * @return key 存在并设置成功返回 true
     */
    boolean expire(KeyBuild key, Duration ttl);

    /**
     * 获取 key 剩余过期时间（秒）；-1 表示未设置过期时间，-2 表示 key 不存在。
     */
    Long getExpire(KeyBuild key);

    /**
     * String 结构操作。
     */
    RedisStringOps strings();

    /**
     * Hash 结构操作。
     */
    RedisHashOps hashes();

    /**
     * Set 结构操作。
     */
    RedisSetOps sets();

    /**
     * ZSet 结构操作。
     */
    RedisZSetOps zsets();
}
