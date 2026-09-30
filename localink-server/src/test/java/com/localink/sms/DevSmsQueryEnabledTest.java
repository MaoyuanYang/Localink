package com.localink.sms;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.RedisStringOps;
import com.localink.common.code.BaseCode;
import com.localink.common.handler.GlobalExceptionHandler;
import com.localink.constant.KeyManage;
import com.localink.controller.DevSmsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W0 dev 取码接口（开关开启语义）：
 * Controller 行为走 standalone MockMvc + mock 依赖；装配语义走轻量 ApplicationContextRunner
 * （withBean 直注册不评估 @Conditional，故用 withUserConfiguration）。
 * 不开 @SpringBootTest 独立上下文——全量测试时多一个 properties 上下文会把 MySQL 151 连接峰值挤爆
 * （本主题全量首跑实录，与 M6/M8 同因）。
 */
class DevSmsQueryEnabledTest {

    private static final String PHONE = "13800138001";

    private final RedisCache redisCache = mock(RedisCache.class);
    private final KeyBuilder keyBuilder = mock(KeyBuilder.class);
    private final RedisStringOps stringOps = mock(RedisStringOps.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DevSmsController(redisCache, keyBuilder))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RedisCache.class, () -> redisCache)
            .withBean(KeyBuilder.class, () -> keyBuilder)
            .withUserConfiguration(DevSmsController.class);

    @BeforeEach
    void stubKeys() {
        when(keyBuilder.build(any(), anyString())).thenReturn(mock(KeyBuild.class));
        when(redisCache.strings()).thenReturn(stringOps);
    }

    @Test
    void queryCodeReturnsSixDigitsWhenPresent() throws Exception {
        when(stringOps.getString(any())).thenReturn("123456");

        mockMvc.perform(get("/api/sms/code/dev").param("phone", PHONE)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("123456"));
    }

    @Test
    void queryCodeRejectedWhenAbsent() throws Exception {
        when(stringOps.getString(any())).thenReturn(null);

        mockMvc.perform(get("/api/sms/code/dev").param("phone", PHONE)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SMS_CODE_EXPIRED.getCode()));
    }

    @Test
    void invalidPhoneRejected() throws Exception {
        mockMvc.perform(get("/api/sms/code/dev").param("phone", "12345")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void beanNotMountedByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DevSmsController.class));
    }

    @Test
    void beanMountedWhenSwitchOn() {
        runner.withPropertyValues("localink.dev.sms-code-query.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(DevSmsController.class));
    }
}
