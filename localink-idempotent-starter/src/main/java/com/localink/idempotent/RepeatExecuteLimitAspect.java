package com.localink.idempotent;

import com.alibaba.fastjson2.JSON;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.lock.AspectProceed;
import com.localink.lock.DistributedLock;
import com.localink.lock.LockType;
import com.localink.lock.SpelKeyResolver;
import com.localink.idempotent.config.IdempotentProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @Order(-100)：幂等在事务拦截器外层——① 标记命中时事务都不必开；② proceed() 返回即事务已提交，
 * 标记写在提交之后，回滚的执行不落标记、重试会再次执行。顺序反了=提交前的标记挡住重试=丢单。
 */
@Slf4j
@Aspect
@Order(-100)
public class RepeatExecuteLimitAspect {

    private static final String NULL_RESULT_JSON = "null";

    private final StringRedisTemplate redisTemplate;
    private final DistributedLock distributedLock;
    private final LocalLockCache localLockCache;
    private final IdempotentProperties properties;
    private final SpelKeyResolver spelKeyResolver = new SpelKeyResolver();

    public RepeatExecuteLimitAspect(StringRedisTemplate redisTemplate, DistributedLock distributedLock,
                                    LocalLockCache localLockCache, IdempotentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.distributedLock = distributedLock;
        this.localLockCache = localLockCache;
        this.properties = properties;
    }

    @Around("@annotation(repeatLimit)")
    public Object around(ProceedingJoinPoint pjp, RepeatExecuteLimit repeatLimit) throws Throwable {
        String bizKey = spelKeyResolver.resolve(pjp, repeatLimit.key());
        String markerKey = properties.getKeyPrefix() + "marker:" + repeatLimit.name() + ":" + bizKey;
        String lockKey = properties.getKeyPrefix() + "lock:" + repeatLimit.name() + ":" + bizKey;

        String fastHit = readMarker(markerKey);
        if (fastHit != null) {
            return replay(pjp, repeatLimit, fastHit);
        }

        ReentrantLock local = localLockCache.get(lockKey);
        if (!local.tryLock(repeatLimit.waitTime(), repeatLimit.timeUnit())) {
            throw new LocalinkException(BaseCode.LOCK_TIMEOUT);
        }
        try {
            String secondHit = readMarker(markerKey);
            if (secondHit != null) {
                return replay(pjp, repeatLimit, secondHit);
            }
            Duration waitTime = Duration.ofMillis(repeatLimit.timeUnit().toMillis(repeatLimit.waitTime()));
            Duration leaseTime = repeatLimit.leaseTime() <= 0
                    ? null
                    : Duration.ofMillis(repeatLimit.timeUnit().toMillis(repeatLimit.leaseTime()));
            return distributedLock.runWithLock(lockKey, LockType.FAIR, waitTime, leaseTime, () -> {
                String thirdHit = readMarker(markerKey);
                if (thirdHit != null) {
                    return replay(pjp, repeatLimit, thirdHit);
                }
                Object result = AspectProceed.proceed(pjp::proceed);
                writeMarker(markerKey, result, repeatLimit);
                return result;
            });
        } catch (RuntimeException e) {
            AspectProceed.rethrowIfWrapped(e);
            throw e;
        } finally {
            local.unlock();
        }
    }

    private String readMarker(String markerKey) {
        try {
            return redisTemplate.opsForValue().get(markerKey);
        } catch (Exception e) {
            log.warn("幂等标记读取失败（视为未命中继续）, key={}", markerKey, e);
            return null;
        }
    }

    private Object replay(ProceedingJoinPoint pjp, RepeatExecuteLimit repeatLimit, String rawMarker) {
        if (repeatLimit.onDuplicate() == DuplicatePolicy.REJECT) {
            throw new LocalinkException(BaseCode.IDEMPOTENT_DUPLICATE);
        }
        if (NULL_RESULT_JSON.equals(rawMarker)) {
            return null;
        }
        Class<?> returnType = ((MethodSignature) pjp.getSignature()).getMethod().getReturnType();
        return JSON.parseObject(rawMarker, returnType);
    }

    private void writeMarker(String markerKey, Object result, RepeatExecuteLimit repeatLimit) {
        if (repeatLimit.markerTtl() <= 0) {
            return;
        }
        String value = result == null ? NULL_RESULT_JSON : JSON.toJSONString(result);
        Duration ttl = Duration.ofMillis(repeatLimit.timeUnit().toMillis(repeatLimit.markerTtl()));
        try {
            redisTemplate.opsForValue().set(markerKey, value, ttl);
        } catch (Exception e) {
            log.error("幂等标记写入失败（不影响本次业务结果，兜底交由业务终审）, key={}", markerKey, e);
        }
    }
}
