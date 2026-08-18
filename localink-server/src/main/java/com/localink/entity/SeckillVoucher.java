package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lk_seckill_voucher")
public class SeckillVoucher {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long voucherId;

    private Integer initStock;

    private Integer stock;

    private Integer minLevel;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
