package com.localink.api.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账流水行（W3 运营看板）：Redis 流水↔DB 单向比对的账本。
 * logType：1 扣减 / 2 恢复；reconciliationStatus：1 待处理 / 4 一致（2/3 为枚举预留）。
 */
@Data
public class ReconcileLogVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long traceId;

    private Integer logType;

    private Integer businessType;

    private Integer reconciliationStatus;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;

    private String detail;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
