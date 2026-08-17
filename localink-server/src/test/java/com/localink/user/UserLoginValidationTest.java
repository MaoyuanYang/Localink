package com.localink.user;

import com.localink.common.code.BaseCode;
import com.localink.common.handler.GlobalExceptionHandler;
import com.localink.controller.UserController;
import com.localink.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserLoginValidationTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new UserController(mock(UserService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void missingCodeReturnsParamError() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("code 验证码不能为空"));
    }

    @Test
    void malformedCodeReturnsParamError() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"code\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }
}
