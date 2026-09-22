package com.localink.ratelimit.aspect;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.ratelimit.Dimension;
import com.localink.ratelimit.RateLimit;
import com.localink.ratelimit.RateLimitAdmin;
import com.localink.ratelimit.RateLimitResult;
import com.localink.ratelimit.RateLimiter;
import com.localink.ratelimit.config.RateLimitProperties;
import com.localink.ratelimit.config.RateLimitProperties.SceneRule;
import com.localink.ratelimit.user.RateLimitUserResolver;
import com.localink.ratelimit.web.RateLimitIpExtractor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;

/**
 * 限流切面（最外层守卫，@Order(-200) 先于幂等/事务）：
 * enabled → 白名单（静态+动态并集，最高优先级放行）→ 封禁（直接拒绝）→ 双维度逐一判定，任一超限即拒。
 * 拒绝抛 LocalinkException(RATE_LIMITED)；Redis 故障 fail-open 放行——限流器不能成为业务故障点。
 * 结构要点：try 只包"判定"，proceed() 在 catch 外单次调用——若 fail-open 包住 proceed，
 * 业务方法自身的异常会被误判为限流器故障并二次执行（幂等灾难）。
 */
@Slf4j
@Aspect
@Order(-200)
public class RateLimitAspect {

    private final RateLimiter rateLimiter;
    private final RateLimitAdmin admin;
    private final RateLimitProperties properties;
    private final ObjectProvider<RateLimitUserResolver> userResolverProvider;
    private volatile boolean resolverMissingWarned;

    public RateLimitAspect(RateLimiter rateLimiter, RateLimitAdmin admin,
                           RateLimitProperties properties,
                           ObjectProvider<RateLimitUserResolver> userResolverProvider) {
        this.rateLimiter = rateLimiter;
        this.admin = admin;
        this.properties = properties;
        this.userResolverProvider = userResolverProvider;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        if (properties.isEnabled()) {
            try {
                checkLimit(rateLimit);
            } catch (LocalinkException e) {
                throw e;
            } catch (Exception e) {
                log.error("限流器故障, fail-open 放行, scene={}", rateLimit.scene(), e);
            }
        }
        return pjp.proceed();
    }

    private void checkLimit(RateLimit rateLimit) {
        String ip = RateLimitIpExtractor.currentIp();
        if (properties.getStaticWhitelist().contains(ip) || admin.isWhitelisted(ip)) {
            return;
        }
        if (admin.isBanned(ip)) {
            throw new LocalinkException(BaseCode.RATE_LIMITED, "访问受限");
        }
        SceneRule rule = properties.getScenes().get(rateLimit.scene());
        if (rule == null || rule.getAlgorithm() == null) {
            log.error("限流场景未配置, 放行（配置缺失不是拒绝的理由）, scene={}", rateLimit.scene());
            return;
        }
        for (Dimension dimension : rateLimit.dimensions()) {
            String dimValue = resolveDimension(dimension);
            if (dimValue == null) {
                continue;
            }
            if (!acquire(rule.effective(dimension), rateLimit.scene() + ":" + dimValue).allowed()) {
                log.warn("限流拒绝, scene={}, dimension={}, key={}", rateLimit.scene(), dimension, dimValue);
                throw new LocalinkException(BaseCode.RATE_LIMITED);
            }
        }
    }

    private String resolveDimension(Dimension dimension) {
        if (dimension == Dimension.IP) {
            return "ip:" + RateLimitIpExtractor.currentIp();
        }
        RateLimitUserResolver resolver = userResolverProvider.getIfAvailable();
        if (resolver == null) {
            if (!resolverMissingWarned) {
                resolverMissingWarned = true;
                log.warn("USER 维度无 RateLimitUserResolver 实现, 该维度降级跳过（匿名流量由 IP 维度兜底）");
            }
            return null;
        }
        String user = resolver.resolveUser();
        return user == null ? null : "user:" + user;
    }

    private RateLimitResult acquire(SceneRule rule, String key) {
        return switch (rule.getAlgorithm()) {
            case TOKEN_BUCKET -> rateLimiter.tryAcquireTokenBucket(key,
                    rule.getCapacity(), rule.getRatePerSec(), 1);
            case SLIDING_WINDOW -> rateLimiter.tryAcquireSlidingWindow(key,
                    rule.getThreshold(), rule.getWindow());
        };
    }
}
