package com.localink.sms;

import com.localink.common.code.BaseCode;
import com.localink.common.handler.GlobalExceptionHandler;
import com.localink.controller.SmsController;
import com.localink.service.SmsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SmsControllerValidationTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SmsController(mock(SmsService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void invalidPhoneReturnsParamError() throws Exception {
        mockMvc.perform(post("/api/sms/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("phone 手机号格式不正确"));
    }

    @Test
    void blankPhoneReturnsParamError() throws Exception {
        mockMvc.perform(post("/api/sms/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }
}
