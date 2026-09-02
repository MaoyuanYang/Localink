package com.localink.cache;

import java.time.Duration;
import java.util.Map;

/**
 * Redis Hash 结构操作。
 */
public interface RedisHashOps {

    /**
     * 写入单个字段。
     */
    void put(KeyBuild key, String field, Object value);

    /**
     * 批量写入字段。
     */
    void putAll(KeyBuild key, Map<String, ?> map);

    /**
     * 批量写入字段并设置 key 过期时间（写入 + EXPIRE 两步）。
     */
    void putAll(KeyBuild key, Map<String, ?> map, Duration ttl);

    /**
     * 读取单个字段并按目标类型反序列化；字段不存在返回 null。
     */
    <T> T get(KeyBuild key, String field, Class<T> type);

    /**
     * 读取全部字段（原始字符串视图）；key 不存在返回空 Map。
     */
    Map<String, String> entries(KeyBuild key);

    /**
     * 判断字段是否存在。
     */
    boolean hasField(KeyBuild key, String field);

    /**
     * 删除字段，返回实际删除的字段数。
     */
    Long delete(KeyBuild key, String... fields);

    /**
     * 字段值原子自增（delta 为负即自减），返回自增后的值。
     */
    Long increment(KeyBuild key, String field, long delta);
}
