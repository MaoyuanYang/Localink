package com.localink.mq;

/**
 * 本地缓存失效广播消息：cache 为 LocalCacheAlias 注册名，key 为该缓存的业务键。
 * 通用通道——未来新增本地缓存只需注册别名并在此复用。
 */
public record CacheInvalidationMessage(String cache, String key) {
}
