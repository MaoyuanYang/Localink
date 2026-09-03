package com.localink.framework.cache;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存条目：data 为业务数据，expireTime 为逻辑过期时刻。
 * key 本身不设物理 TTL——物理永不过期，由 expireTime 判定是否触发异步重建。
 */
@Data
public class LogicalExpiryEntry<T> {

    private T data;

    private LocalDateTime expireTime;
}
