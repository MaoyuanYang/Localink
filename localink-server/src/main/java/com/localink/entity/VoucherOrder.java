package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lk_voucher_order")
public class VoucherOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long voucherId;

    private Integer status;

    private Integer reconciliationStatus;

    private LocalDateTime createTime;

    private LocalDateTime closeTime;

    private LocalDateTime updateTime;
}
