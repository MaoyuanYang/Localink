package com.localink.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级限流（M3.14）：算法与参数全部来自场景配置（localink.ratelimit.scenes.{scene}），
 * 注解只声明"哪个场景、哪些维度"——配置与代码分离，调参不发版。
 * 切面为最外层守卫（@Order(-200)，先于幂等/事务）；Redis 故障 fail-open 放行业务。
 * 拒绝抛 LocalinkException(RATE_LIMITED)，由全局异常处理器统一出参。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 场景名（对应 scenes 配置键，也作为限流 key 的一段）。
     */
    String scene();

    /**
     * 限流维度；USER 维度需容器内存在 {@link user.RateLimitUserResolver} 实现，
     * 缺失时降级为仅 IP 维度（启动警告）。
     */
    Dimension[] dimensions() default {Dimension.IP};
}
