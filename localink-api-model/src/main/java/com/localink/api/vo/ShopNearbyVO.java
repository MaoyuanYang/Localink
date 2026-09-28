package com.localink.api.vo;

import lombok.Data;

/**
 * 附近商户视图（M6-F）：distance 单位米，列表已按距离升序。
 */
@Data
public class ShopNearbyVO {

    private Long id;

    private String name;

    private Long typeId;

    private String address;

    private Double distance;
}
