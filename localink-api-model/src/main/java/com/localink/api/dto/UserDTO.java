package com.localink.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class UserDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String phone;

    private String nickName;

    private String icon;

    private Integer level;
}
