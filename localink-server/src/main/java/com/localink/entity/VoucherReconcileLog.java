package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀对账流水：三层对账（Redis 流水 ↔ 本表 ↔ 订单）的中间层。
 * 扣减行随建单事务同写（logType=1），恢复行随回滚成功后写（logType=2）；
 * M5.1 定时比对"Redis 扣了但 DB 无单"。一单同一动作一行（uk_order_log 幂等）。
 */
@Data
@TableName("lk_voucher_reconcile_log")
public class VoucherReconcileLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    /**
     * 下单用户（分片冗余键，M4 分库后随 user_id 路由）。
     */
    private Long userId;

    private Long voucherId;

    /**
     * 资格生命周期 ID（扣减 Lua 落 Redis 流水的同一 ID，串联 Redis/DB/回滚）。
     */
    private Long traceId;

    /**
     * Kafka 消息 UUID（消费幂等关联，扣减行携带；恢复行可空）。
     */
    private String messageId;

    /**
     * 1 扣减 / 2 恢复。
     */
    private Integer logType;

    /**
     * 1 下单成功 / 2 下单超时 / 3 下单失败。
     */
    private Integer businessType;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;

    /**
     * 1 待处理 / 2 异常 / 3 不一致 / 4 一致（M5.1 起维护）。
     */
    private Integer reconciliationStatus;

    private String detail;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
