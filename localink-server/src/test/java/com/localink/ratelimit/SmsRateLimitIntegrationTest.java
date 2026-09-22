package com.localink.ratelimit;

import com.localink.cache.KeyBuilder;
import com.localink.common.code.BaseCode;
import com.localink.constant.KeyManage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3.14 短信限流场景接入验证：首个 @RateLimit 生产场景（滑动窗口 1 次/60s，IP 维度）。
 * MockMvc 下 remoteAddr 固定 127.0.0.1，每例清窗口 key 隔离；手机号每例随机避免验证码 key 干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmsRateLimitIntegrationTest {

    private static final String WINDOW_KEY = "lk:rl:sw:sms-send:ip:127.0.0.1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private KeyBuilder keyBuilder;

    private String phone;

    @BeforeEach
    void setUp() {
        phone = "139" + String.format("%08d", (int) (Math.random() * 100_000_000));
        cleanKeys();
    }

    @AfterEach
    void cleanKeys() {
        redisTemplate.delete(WINDOW_KEY);
        redisTemplate.delete(keyBuilder.build(KeyManage.SMS_CODE, phone).getKey());
    }

    @Test
    void firstSendPassesAndImmediateSecondIsRejected() throws Exception {
        String body = "{\"phone\":\"" + phone + "\"}";
        mockMvc.perform(post("/api/sms/code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/sms/code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.RATE_LIMITED.getCode()))
                .andExpect(jsonPath("$.message").value(BaseCode.RATE_LIMITED.getMessage()));
    }

    @Test
    void whitelistedIpBypassesSmsRateLimit() throws Exception {
        redisTemplate.opsForSet().add("lk:rl:whitelist", "127.0.0.1");
        try {
            for (int i = 0; i < 3; i++) {
                // 每次新手机号：隔离 SmsService 自身的 60s 重发限制，本用例只验证限流层放行
                String body = "{\"phone\":\"" + nextPhone() + "\"}";
                mockMvc.perform(post("/api/sms/code").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0));
            }
        } finally {
            redisTemplate.opsForSet().remove("lk:rl:whitelist", "127.0.0.1");
        }
    }

    private String nextPhone() {
        return "139" + String.format("%08d", (int) (Math.random() * 100_000_000));
    }
}
