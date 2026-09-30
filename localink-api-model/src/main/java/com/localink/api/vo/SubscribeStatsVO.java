package com.localink.api.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订阅运营统计（W2）：订阅队列规模与状态分布来自 Redis（ZSet/Hash），
 * 预通知标记来自 notice:sent（SETNX 防重，TTL 1 天），活动窗口回显自券信息。
 */
@Data
public class SubscribeStatsVO {

    private Long queueSize;

    private Long subscribedCount;

    private Long grantedCount;

    private Boolean noticeSent;

    private String title;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
