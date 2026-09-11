package com.localink.constant;

/**
 * 布隆过滤器别名登记处：与 application.yml 的 localink.cache.bloom.filters 键一一对应，
 * 模板镜像登记在 KeyManage，禁止散落硬编码。
 */
public final class BloomFilterAlias {

    /**
     * 商户 ID 布隆过滤器（key 模板见 KeyManage.SHOP_BLOOM）。
     */
    public static final String SHOP = "shop";

    private BloomFilterAlias() {
    }
}
