package com.localink.cache;

import java.time.Duration;
import java.util.List;

/**
 * Redis String 结构操作。
 */
public interface RedisStringOps {

    /**
     * 写入缓存（不过期）。
     */
    void set(KeyBuild key, Object value);

    /**
     * 写入缓存并设置过期时间（SET + EX 原子）。
     */
    void set(KeyBuild key, Object value, Duration ttl);

    /**
     * 仅当 key 不存在时写入并设置过期时间（SET NX EX 原子）。
     *
     * @return 写入成功返回 true；key 已存在返回 false
     */
    boolean setIfAbsent(KeyBuild key, Object value, Duration ttl);

    /**
     * 读取并按目标类型反序列化；key 不存在返回 null。
     */
    <T> T get(KeyBuild key, Class<T> type);

    /**
     * 读取原始字符串；key 不存在返回 null。
     */
    String getString(KeyBuild key);

    /**
     * 读取 JSON 数组并按元素类型反序列化；key 不存在返回 null。
     */
    <T> List<T> getList(KeyBuild key, Class<T> elementType);

    /**
     * 读取并删除（GETDEL 原子）；key 不存在返回 null。
     */
    String getAndDelete(KeyBuild key);

    /**
     * 原子自增（delta 为负即自减），返回自增后的值。
     */
    Long increment(KeyBuild key, long delta);
}
