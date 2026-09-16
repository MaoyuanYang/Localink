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

    /**
     * 券类型冗余（1 普通 / 2 秒杀）：秒杀"一人一单"条件唯一索引的判据。
     */
    private Integer voucherType;

    private Integer status;

    private Integer reconciliationStatus;

    private LocalDateTime createTime;

    private LocalDateTime closeTime;

    private LocalDateTime updateTime;
}
