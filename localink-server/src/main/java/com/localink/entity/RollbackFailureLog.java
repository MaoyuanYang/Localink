package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Redis 回滚终失败记录：M3.11 写入，M5.2 扫描做补偿与告警。
 */
@Data
@TableName("lk_rollback_failure_log")
public class RollbackFailureLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long voucherId;

    private Long userId;

    /**
     * 订单 ID（建单前失败可空）。
     */
    private Long orderId;

    private Long traceId;

    /**
     * Lua 返回码（对应 BaseCode 秒杀段，可空）。
     */
    private Integer resultCode;

    private Integer retryAttempts;

    /**
     * 来源组件（REQUEST_SEND / CONSUME_EXHAUSTED / STALE_DROP）。
     */
    private String source;

    private String detail;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
