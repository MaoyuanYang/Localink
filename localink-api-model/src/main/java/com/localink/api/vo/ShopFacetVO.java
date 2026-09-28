package com.localink.api.vo;

import lombok.Data;

/**
 * 商户分面（M6-D）：搜索全集按 shopId 聚合的侧栏条目，count 为该商户命中数。
 */
@Data
public class ShopFacetVO {

    private Long shopId;

    private String shopName;

    private Long count;

    public static ShopFacetVO of(Long shopId, String shopName, Long count) {
        ShopFacetVO vo = new ShopFacetVO();
        vo.setShopId(shopId);
        vo.setShopName(shopName);
        vo.setCount(count);
        return vo;
    }
}
