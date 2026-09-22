package com.localink.id.config;

import com.localink.id.SnowflakeIdGenerator;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M4.1 起步：固定机器位（配置声明）。M4.2 引入 Redis 轮转分配后，
 * 未配置固定值时自动走分配器。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.id")
public class IdProperties {

    /**
     * 机器 ID（0-31）；null 表示未固定，交给分配策略（M4.2）。
     */
    private Long workId;

    /**
     * 数据中心 ID（0-31）；null 同上。
     */
    private Long dataCenterId;

    /**
     * 时钟回拨容忍上限（毫秒），超出拒绝发号。
     */
    private long maxBackwardMs = 5;
}
