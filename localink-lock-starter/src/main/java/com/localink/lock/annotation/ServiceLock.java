package com.localink.lock.annotation;

import com.localink.lock.LockType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 方法级分布式锁。最终 Redis key = localink.lock.key-prefix + name + ":" + key 的 SpEL 求值结果。
 * 切面在事务拦截器外层（先拿锁再开事务，提交后才释放锁），标注方法应保持事务注解不变。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ServiceLock {

    /**
     * 锁名（业务语义段），如 "seckill:order"。
     */
    String name();

    /**
     * 变量段，SpEL 表达式，可引用方法参数名，如 "#voucherId"。
     */
    String key();

    LockType type() default LockType.REENTRANT;

    /**
     * 获取锁的最长等待，超时抛 LOCK_TIMEOUT。
     */
    long waitTime() default 3;

    /**
     * 持锁时长；非正数走看门狗自动续期（默认）。
     */
    long leaseTime() default -1;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
