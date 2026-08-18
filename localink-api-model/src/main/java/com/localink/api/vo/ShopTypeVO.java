package com.localink.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class ShopTypeVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    private String icon;

    private Integer sort;
}
