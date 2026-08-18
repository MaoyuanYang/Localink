package com.localink.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long shopId;

    private String title;

    private String subTitle;

    private String rules;

    private Long payValue;

    private Long actualValue;

    private Integer type;

    private Integer status;

    private LocalDateTime createTime;
}
