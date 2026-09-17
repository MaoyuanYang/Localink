package com.localink.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 方法级幂等：结果标记 → 本地公平锁 → 分布式公平锁三级防护，同一 name+key 只执行一次。
 * 切面在事务拦截器外层（@Order(-100)）：标记命中时事务都不开；标记在事务提交后写入——
 * 回滚的执行不落标记，重试/重投会再次执行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatExecuteLimit {

    /**
     * 业务名（幂等命名空间）。
     */
    String name();

    /**
     * 幂等键，SpEL 表达式引用方法参数，如 "#message.orderId()"。
     */
    String key();

    /**
     * 锁等待上限（秒），超时抛 LOCK_TIMEOUT。
     */
    long waitTime() default 3;

    /**
     * 结果标记 TTL（秒）；非正数表示不写标记（只挡并发不挡重复）。
     */
    long markerTtl() default 86400;

    /**
     * 标记命中时的处置：SKIP 静默跳过（消费场景，回放已存结果或返回 null）/
     * REJECT 抛 IDEMPOTENT_DUPLICATE（交互场景防双击）。
     */
    DuplicatePolicy onDuplicate() default DuplicatePolicy.SKIP;

    /**
     * 保留给 leaseTime 显式声明的扩展位；默认看门狗。
     */
    long leaseTime() default -1;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
