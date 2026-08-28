package com.localink.cache;

import java.util.Set;

/**
 * Redis Set 结构操作。
 */
public interface RedisSetOps {

    /**
     * 添加元素，返回实际新增的元素数。
     */
    Long add(KeyBuild key, Object... values);

    /**
     * 移除元素，返回实际移除的元素数。
     */
    Long remove(KeyBuild key, Object... values);

    /**
     * 判断元素是否在集合中。
     */
    boolean isMember(KeyBuild key, Object value);

    /**
     * 读取全部元素并按目标类型反序列化；key 不存在返回空集合。
     */
    <T> Set<T> members(KeyBuild key, Class<T> type);

    /**
     * 求两个集合的交集并按目标类型反序列化；任一 key 不存在返回空集合。
     */
    <T> Set<T> intersect(KeyBuild key, KeyBuild otherKey, Class<T> type);

    /**
     * 集合元素总数；key 不存在返回 0。
     */
    Long size(KeyBuild key);
}
