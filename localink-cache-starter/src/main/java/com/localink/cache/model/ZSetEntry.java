package com.localink.cache.model;

/**
 * ZSet 带分数查询的返回载体。
 */
public record ZSetEntry<T>(T value, double score) {
}
