package com.localink.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ShopDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @NotBlank(message = "商户名称不能为空")
    private String name;

    @NotNull(message = "商户类型不能为空")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long typeId;

    private String images;

    private String area;

    @NotBlank(message = "商户地址不能为空")
    private String address;

    @NotNull(message = "经度不能为空")
    private Double longitude;

    @NotNull(message = "纬度不能为空")
    private Double latitude;

    private Long avgPrice;

    private String openHours;
}
