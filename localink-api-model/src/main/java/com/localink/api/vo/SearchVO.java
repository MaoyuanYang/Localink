package com.localink.api.vo;

import lombok.Data;

import java.util.List;

/**
 * 搜索结果视图（M6-D）：nextSearchAfter 为 search_after 游标（sort 键以 "|" 拼接，回传原样
 * 续翻），null 到底；shopFacets 为商户分面侧栏（聚合不受 shopId 筛选影响，保搜索全集）。
 */
@Data
public class SearchVO {

    private List<PostSearchVO> records;

    private String nextSearchAfter;

    private List<ShopFacetVO> shopFacets;

    public static SearchVO of(List<PostSearchVO> records, String nextSearchAfter, List<ShopFacetVO> shopFacets) {
        SearchVO vo = new SearchVO();
        vo.setRecords(records);
        vo.setNextSearchAfter(nextSearchAfter);
        vo.setShopFacets(shopFacets);
        return vo;
    }
}
