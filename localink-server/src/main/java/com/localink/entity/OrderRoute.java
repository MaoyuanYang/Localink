package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单路由表（M4.5，非分片落 ds_0）：orderId → 分片键（user_id/voucher_id）反查，
 * 管理端/反查路径据此定位物理库表，免全分片广播。建单事务同写；
 * 跨库写有缝隙（订单与路由非同一物理连接），路由缺失时以广播兜底——路由是优化不是正确性依赖。
 */
@Data
@TableName("lk_order_route")
public class OrderRoute {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    private Long userId;

    private Long voucherId;

    private LocalDateTime createTime;
}
