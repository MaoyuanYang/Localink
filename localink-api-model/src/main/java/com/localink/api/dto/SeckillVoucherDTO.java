package com.localink.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SeckillVoucherDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @NotNull(message = "所属商户不能为空")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long shopId;

    @NotBlank(message = "券标题不能为空")
    private String title;

    private String subTitle;

    private String rules;

    @NotNull(message = "支付金额不能为空")
    @Min(value = 0, message = "支付金额不能为负数")
    private Long payValue;

    @NotNull(message = "抵扣金额不能为空")
    @Min(value = 1, message = "抵扣金额必须大于0")
    private Long actualValue;

    private Integer status;

    @NotNull(message = "库存不能为空")
    @Min(value = 1, message = "库存必须大于0")
    private Integer stock;

    @NotNull(message = "会员等级门槛不能为空")
    @Min(value = 0, message = "会员等级门槛最低为0")
    @Max(value = 9, message = "会员等级门槛最高为9")
    private Integer minLevel;

    @NotNull(message = "开抢时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
