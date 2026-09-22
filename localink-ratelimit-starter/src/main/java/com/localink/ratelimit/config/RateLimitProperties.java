package com.localink.ratelimit.config;

import com.localink.ratelimit.Algorithm;
import com.localink.ratelimit.Dimension;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 限流配置（M3.14 扩展场景化）：scenes 按场景名声明算法与参数，@RateLimit 注解按 scene 引用——
 * 调参不发版。静态白名单与 Redis 动态白/黑名单（RateLimitAdmin）并集生效。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.ratelimit")
public class RateLimitProperties {

    /**
     * 限流 key 统一前缀（默认 lk:，与 KeyManage 环境前缀约定一致）。
     */
    private String keyPrefix = "lk:";

    /**
     * 限流器总开关：false 时切面直接放行（压测/应急口径）；Redis 故障亦 fail-open。
     */
    private boolean enabled = true;

    /**
     * 静态白名单（yml 声明，运维信任 IP）；与 Redis 动态白名单并集。
     */
    private List<String> staticWhitelist = new ArrayList<>();

    /**
     * 场景规则表：key=场景名（@RateLimit.scene()），value=算法+参数。
     */
    private Map<String, SceneRule> scenes = new HashMap<>();

    @Getter
    @Setter
    public static class SceneRule {

        /**
         * 算法（必填）。
         */
        private Algorithm algorithm;

        /**
         * 令牌桶：桶容量（最大突发额度）。
         */
        private Integer capacity;

        /**
         * 令牌桶：补充速率（个/秒，可小于 1）。
         */
        private Double ratePerSec;

        /**
         * 滑动窗口：窗口内最大放行数。
         */
        private Integer threshold;

        /**
         * 滑动窗口：窗口长度。
         */
        private Duration window;

        /**
         * 维度级参数覆盖：key=维度，value=同结构规则（非 null 字段覆盖默认值）。
         * 双维度共享同一场景参数时，共享维度（IP）会比单账号维度（USER）先耗尽——
         * 生产口径通常是"IP 宽、用户严"（如 IP 100/min、用户 5/min），故支持按维度差异化。
         */
        private Map<Dimension, SceneRule> overrides = new HashMap<>();

        /**
         * 生效规则：默认字段 + 指定维度的非 null 覆盖字段。
         */
        public SceneRule effective(Dimension dimension) {
            SceneRule override = overrides.get(dimension);
            if (override == null) {
                return this;
            }
            SceneRule merged = new SceneRule();
            merged.setAlgorithm(override.algorithm != null ? override.algorithm : algorithm);
            merged.setCapacity(override.capacity != null ? override.capacity : capacity);
            merged.setRatePerSec(override.ratePerSec != null ? override.ratePerSec : ratePerSec);
            merged.setThreshold(override.threshold != null ? override.threshold : threshold);
            merged.setWindow(override.window != null ? override.window : window);
            return merged;
        }
    }
}
