package com.localink.lock.aspect;

import com.localink.lock.AspectProceed;
import com.localink.lock.DistributedLock;
import com.localink.lock.SpelKeyResolver;
import com.localink.lock.annotation.ServiceLock;
import com.localink.lock.config.LockProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;

import java.time.Duration;

/**
 * @Order(0)：先于默认顺序的事务拦截器生效，保证"拿锁 → 开事务 → 提交 → 释放锁"，
 * 避免锁内事务尚未提交、锁已被下一个线程获取而读到旧数据的窗口。
 */
@Aspect
@Order(0)
public class ServiceLockAspect {

    private final DistributedLock distributedLock;
    private final LockProperties lockProperties;
    private final SpelKeyResolver spelKeyResolver = new SpelKeyResolver();

    public ServiceLockAspect(DistributedLock distributedLock, LockProperties lockProperties) {
        this.distributedLock = distributedLock;
        this.lockProperties = lockProperties;
    }

    @Around("@annotation(serviceLock)")
    public Object around(ProceedingJoinPoint pjp, ServiceLock serviceLock) throws Throwable {
        String key = buildKey(pjp, serviceLock);
        Duration waitTime = Duration.ofMillis(serviceLock.timeUnit().toMillis(serviceLock.waitTime()));
        Duration leaseTime = serviceLock.leaseTime() <= 0
                ? null
                : Duration.ofMillis(serviceLock.timeUnit().toMillis(serviceLock.leaseTime()));
        try {
            return distributedLock.runWithLock(key, serviceLock.type(), waitTime, leaseTime,
                    () -> AspectProceed.proceed(pjp::proceed));
        } catch (RuntimeException e) {
            AspectProceed.rethrowIfWrapped(e);
            throw e;
        }
    }

    private String buildKey(ProceedingJoinPoint pjp, ServiceLock serviceLock) {
        return lockProperties.getKeyPrefix() + serviceLock.name() + ":"
                + spelKeyResolver.resolve(pjp, serviceLock.key());
    }
}
