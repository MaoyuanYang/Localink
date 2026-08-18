package com.localink.cache;

import com.localink.cache.model.ZSetEntry;

import java.time.Duration;
import java.util.Set;

/**
 * Redis ZSet 结构操作。
 */
public interface RedisZSetOps {

    /**
     * 添加元素（score 已存在则更新），返回是否新增。
     */
    boolean add(String key, Object value, double score);

    /**
     * 添加元素并设置 key 过期时间（写入 + EXPIRE 两步）。
     */
    boolean add(String key, Object value, double score, Duration ttl);

    /**
     * 原子增加元素 score（delta 为负即减少），返回增加后的 score。
     */
    Double incrementScore(String key, Object value, double delta);

    /**
     * 移除元素，返回实际移除的元素数。
     */
    Long remove(String key, Object... values);

    /**
     * 集合元素总数；key 不存在返回 0。
     */
    Long size(String key);

    /**
     * 元素排名（score 从小到大，0 为第一）；元素不存在返回 null。
     */
    Long rank(String key, Object value);

    /**
     * 元素排名（score 从大到小，0 为第一）；元素不存在返回 null。
     */
    Long reverseRank(String key, Object value);

    /**
     * 读取元素 score；元素不存在返回 null。
     */
    Double score(String key, Object value);

    /**
     * 按下标范围读取（score 从小到大，含 end）。
     */
    <T> Set<T> range(String key, long start, long end, Class<T> type);

    /**
     * 按下标范围读取（score 从大到小，含 end）。
     */
    <T> Set<T> reverseRange(String key, long start, long end, Class<T> type);

    /**
     * 按下标范围读取并携带 score（score 从大到小，含 end）。
     */
    <T> Set<ZSetEntry<T>> reverseRangeWithScore(String key, long start, long end, Class<T> type);

    /**
     * 按 score 范围读取（score 从大到小），offset/count 用于滚动分页。
     */
    <T> Set<T> reverseRangeByScore(String key, double min, double max, long offset, long count, Class<T> type);
}
