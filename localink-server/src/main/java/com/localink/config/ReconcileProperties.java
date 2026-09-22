package com.localink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对账任务配置（M5-A）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.reconcile")
public class ReconcileProperties {

    /**
     * 对账任务总开关（压测/应急时可停）。
     */
    private boolean enabled = true;

    /**
     * 调度间隔（毫秒，fixedDelay 语义：上一轮跑完后再计时）。
     */
    private long intervalMs = 300_000;

    /**
     * 差异判定宽限期（分钟）：流水时间戳距今不足此值视为"建单在途"跳过——
     * 异步链路毫秒级完成，2 分钟仍无 DB 行即判定消息/建单丢失。
     */
    private long graceMinutes = 2;

    /**
     * 失败表行龄告警阈值（小时）：超过仍存在说明重试长期不收敛，需人工介入。
     */
    private long failureAgeAlarmHours = 24;
}
