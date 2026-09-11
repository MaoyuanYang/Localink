package com.localink.cache;

/**
 * 进程内本地缓存薄视图。不提供加载重载——miss 回源与并发防护由上层缓存（Redis）承担。
 *
 * @param <K> 键类型
 * @param <V> 值类型（进程内对象引用，无序列化）
 */
public interface LocalCache<K, V> {

    /**
     * 读取本地缓存，不存在或已过期返回 null。
     */
    V getIfPresent(K key);

    /**
     * 写入本地缓存，key 或 value 为 null 时静默忽略（空值语义不进本地缓存）。
     */
    void put(K key, V value);

    /**
     * 失效单条本地缓存。
     */
    void invalidate(K key);

    /**
     * 强制执行挂起的维护操作（容量淘汰等异步收尾），需要确定性容量状态时调用。
     */
    void cleanUp();
}
