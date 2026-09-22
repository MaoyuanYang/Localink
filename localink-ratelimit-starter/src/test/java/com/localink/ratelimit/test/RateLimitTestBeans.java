package com.localink.ratelimit.test;

import com.localink.ratelimit.Dimension;
import com.localink.ratelimit.RateLimit;
import com.localink.ratelimit.user.RateLimitUserResolver;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 切面集成测试靶子（组件扫描装配）：注解方法只做计数，观察放行/拒绝；用户维度经 TestUserResolver 可控切换。
 * 计数器必须 static——注入的是 CGLIB 代理对象，实例字段在代理上是 null（代理只转发方法调用，字段读不到目标对象）。
 */
class RateLimitTestBeans {

    static final AtomicInteger WINDOW_CALLS = new AtomicInteger();
    static final AtomicInteger BUCKET_CALLS = new AtomicInteger();
    static final AtomicInteger USER_CALLS = new AtomicInteger();
    static final AtomicInteger THROWING_CALLS = new AtomicInteger();

    static final AtomicReference<String> CURRENT_USER = new AtomicReference<>();

    @Component
    static class AnnotatedService {

        @RateLimit(scene = "test-window", dimensions = Dimension.IP)
        void windowScene() {
            WINDOW_CALLS.incrementAndGet();
        }

        @RateLimit(scene = "test-bucket", dimensions = Dimension.IP)
        void bucketScene() {
            BUCKET_CALLS.incrementAndGet();
        }

        @RateLimit(scene = "test-user", dimensions = {Dimension.IP, Dimension.USER})
        void userScene() {
            USER_CALLS.incrementAndGet();
        }

        /**
         * fail-open 结构验证靶子：业务自身抛业务异常，必须原样透传且不得因误判重试而重复执行。
         */
        @RateLimit(scene = "test-window", dimensions = Dimension.IP)
        void throwingScene() {
            THROWING_CALLS.incrementAndGet();
            throw new com.localink.common.exception.LocalinkException(
                    com.localink.common.code.BaseCode.PARAM_ERROR, "业务自身异常");
        }
    }

    @Component
    static class TestUserResolver implements RateLimitUserResolver {

        @Override
        public String resolveUser() {
            return CURRENT_USER.get();
        }
    }
}
