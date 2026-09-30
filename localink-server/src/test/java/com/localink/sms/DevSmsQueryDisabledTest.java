package com.localink.sms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W0 dev 取码接口默认关闭：Bean 不装配，路由不存在 → 404。
 * 默认配置上下文（与其他 @SpringBootTest 共享缓存），无需 DirtiesContext。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DevSmsQueryDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void devCodeEndpointNotMountedWhenDisabled() throws Exception {
        mockMvc.perform(get("/api/sms/code/dev").param("phone", "13800138000"))
                .andExpect(status().isNotFound());
    }
}
