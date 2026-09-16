package com.localink.lock.aspect;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.lock.DistributedLock;
import com.localink.lock.annotation.ServiceLock;
import com.localink.lock.config.LockProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.TypedValue;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @Order(0)：先于默认顺序的事务拦截器生效，保证"拿锁 → 开事务 → 提交 → 释放锁"，
 * 避免锁内事务尚未提交、锁已被下一个线程获取而读到旧数据的窗口。
 */
@Aspect
@Order(0)
public class ServiceLockAspect {

    private final DistributedLock distributedLock;
    private final LockProperties lockProperties;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ConcurrentMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

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
            return distributedLock.runWithLock(key, serviceLock.type(), waitTime, leaseTime, () -> proceed(pjp));
        } catch (CheckedProceedThrowable wrapper) {
            throw wrapper.getCause();
        }
    }

    private String buildKey(ProceedingJoinPoint pjp, ServiceLock serviceLock) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                TypedValue.NULL, method, pjp.getArgs(), parameterNameDiscoverer);
        String evaluated = expressionCache
                .computeIfAbsent(serviceLock.key(), parser::parseExpression)
                .getValue(context, String.class);
        if (evaluated == null || evaluated.isBlank()) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "ServiceLock key 求值为空: " + serviceLock.key());
        }
        return lockProperties.getKeyPrefix() + serviceLock.name() + ":" + evaluated;
    }

    private Object proceed(ProceedingJoinPoint pjp) {
        try {
            return pjp.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new CheckedProceedThrowable(t);
        }
    }

    /**
     * 受检异常载体：Supplier 不允许抛出受检异常，穿越锁模板后在切面入口拆包重抛。
     */
    private static final class CheckedProceedThrowable extends RuntimeException {
        CheckedProceedThrowable(Throwable cause) {
            super(cause);
        }
    }
}
