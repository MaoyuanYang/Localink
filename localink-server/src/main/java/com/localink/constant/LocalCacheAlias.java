package com.localink.constant;

/**
 * 本地缓存别名登记处：与 application.yml 的 localink.cache.local.caches 键一一对应，
 * 禁止散落硬编码。
 */
public final class LocalCacheAlias {

    /**
     * 商户详情本地缓存（L1，进程内 ShopVO）。
     */
    public static final String SHOP = "shop";

    private LocalCacheAlias() {
    }
}
