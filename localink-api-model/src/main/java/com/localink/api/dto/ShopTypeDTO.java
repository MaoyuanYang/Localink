package com.localink.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShopTypeDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @NotBlank(message = "类型名称不能为空")
    private String name;

    private String icon;

    private Integer sort;
}
