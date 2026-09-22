package com.localink.ratelimit.test;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.ratelimit.RateLimitAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

import static com.localink.ratelimit.test.RateLimitTestBeans.BUCKET_CALLS;
import static com.localink.ratelimit.test.RateLimitTestBeans.CURRENT_USER;
import static com.localink.ratelimit.test.RateLimitTestBeans.THROWING_CALLS;
import static com.localink.ratelimit.test.RateLimitTestBeans.USER_CALLS;
import static com.localink.ratelimit.test.RateLimitTestBeans.WINDOW_CALLS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.14 切面组装验证：场景配置驱动、IP/用户双维度独立计数（维度级参数覆盖）、
 * 静态+动态白名单、封禁、业务异常不被 fail-open 吞掉且不重复执行。
 */
@SpringBootTest
class RateLimitAspectIntegrationTest {

    @Autowired
    private RateLimitTestBeans.AnnotatedService service;

    @Autowired
    private RateLimitAdmin admin;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetState() {
        CURRENT_USER.set(null);
        WINDOW_CALLS.set(0);
        BUCKET_CALLS.set(0);
        USER_CALLS.set(0);
        THROWING_CALLS.set(0);
        redisTemplate.delete(Arrays.asList(
                "lk:rl:tb:test-bucket:ip:1.1.1.1",
                "lk:rl:sw:test-window:ip:1.1.1.1", "lk:rl:sw:test-window:ip:1.1.1.2",
                "lk:rl:sw:test-user:ip:1.1.1.1",
                "lk:rl:sw:test-user:user:100", "lk:rl:sw:test-user:user:200",
                "lk:rl:whitelist", "lk:rl:banned"));
    }

    @AfterEach
    void cleanContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void mockIp(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void windowSceneRejectsThirdCallFromSameIp() {
        mockIp("1.1.1.1");
        service.windowScene();
        service.windowScene();
        LocalinkException rejected = assertThrows(LocalinkException.class, service::windowScene);
        assertEquals(BaseCode.RATE_LIMITED.getCode(), rejected.getCode());
        assertEquals(2, WINDOW_CALLS.get(), "被拒调用不得执行业务方法");
    }

    @Test
    void differentIpsCountIndependently() {
        mockIp("1.1.1.1");
        service.windowScene();
        service.windowScene();
        mockIp("1.1.1.2");
        service.windowScene();
        service.windowScene();
        assertEquals(4, WINDOW_CALLS.get(), "各 IP 独立计数互不挤占");
    }

    @Test
    void staticWhitelistBypassesLimit() {
        mockIp("10.10.10.10");
        for (int i = 0; i < 5; i++) {
            service.windowScene();
        }
        assertEquals(5, WINDOW_CALLS.get(), "静态白名单 IP 不受限流");
    }

    @Test
    void dynamicWhitelistAndBanTakeEffectImmediately() {
        mockIp("1.1.1.1");
        admin.whitelist("1.1.1.1");
        for (int i = 0; i < 5; i++) {
            service.windowScene();
        }
        assertEquals(5, WINDOW_CALLS.get(), "动态白名单应跳过限流");

        admin.dewhitelist("1.1.1.1");
        admin.ban("1.1.1.1");
        LocalinkException rejected = assertThrows(LocalinkException.class, service::windowScene);
        assertEquals(BaseCode.RATE_LIMITED.getCode(), rejected.getCode());
        assertEquals("访问受限", rejected.getMessage());
        assertTrue(admin.isBanned("1.1.1.1"));
    }

    @Test
    void userDimensionOverriddenIndependentlyOfSharedIpDimension() {
        mockIp("1.1.1.1");
        CURRENT_USER.set("100");
        service.userScene();

        CURRENT_USER.set("100");
        LocalinkException sameUser = assertThrows(LocalinkException.class, service::userScene,
                "用户维度 override threshold=1，同用户第 2 次应拒");
        assertEquals(BaseCode.RATE_LIMITED.getCode(), sameUser.getCode());

        CURRENT_USER.set("200");
        service.userScene();
        assertEquals(2, USER_CALLS.get(),
                "换用户获得独立额度；共享 IP 维度（threshold=100）不得先于用户维度耗尽");
    }

    @Test
    void tokenBucketSceneRejectsWhenCapacityExhausted() {
        mockIp("1.1.1.1");
        service.bucketScene();
        service.bucketScene();
        LocalinkException rejected = assertThrows(LocalinkException.class, service::bucketScene);
        assertEquals(BaseCode.RATE_LIMITED.getCode(), rejected.getCode());
        assertEquals(2, BUCKET_CALLS.get());
    }

    @Test
    void businessExceptionPassesThroughWithoutReexecution() {
        mockIp("1.1.1.1");
        LocalinkException business = assertThrows(LocalinkException.class, service::throwingScene);
        assertEquals(BaseCode.PARAM_ERROR.getCode(), business.getCode(),
                "业务异常必须原样透传，不得被 fail-open 吞掉或误判");
        assertEquals(1, THROWING_CALLS.get(), "业务方法只能执行一次（误重试即幂等灾难）");
    }
}
