package com.localink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 热榜配置（M6-E）：halfLifeHours 是衰减半衰期（λ=ln2/半衰期），即榜单记忆长度——
 * "N 小时前的帖权重剩一半"；weights 是行为权重旋钮（互动深度 赞>评>看）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.hot")
public class HotProperties {

    private boolean enabled = true;

    private long intervalMs = 300_000;

    /** 候选集"近期新帖"窗口（天）。 */
    private int recencyDays = 7;

    /** 衰减半衰期（小时）。 */
    private double halfLifeHours = 72;

    private Weights weights = new Weights();

    @Getter
    @Setter
    public static class Weights {

        private int like = 5;
        private int comment = 3;
        private int view = 1;
    }
}
