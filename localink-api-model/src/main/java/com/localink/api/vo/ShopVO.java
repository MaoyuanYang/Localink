package com.localink.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class ShopVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long typeId;

    private String images;

    private String area;

    private String address;

    private Double longitude;

    private Double latitude;

    private Long avgPrice;

    private Integer sold;

    private Integer comments;

    private Integer score;

    private String openHours;
}
