package com.localink.handler;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.common.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void businessExceptionReturnsHttp200WithBusinessCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("手机号格式不正确"));
    }

    @Test
    void noResourceFoundReturnsHttp404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(BaseCode.NOT_FOUND.getCode()));
    }

    @Test
    void unknownExceptionReturnsHttp500WithoutStackTrace() throws Exception {
        mockMvc.perform(get("/test/system"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(BaseCode.SYSTEM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(BaseCode.SYSTEM_ERROR.getMessage()));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/business")
        public void business() {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "手机号格式不正确");
        }

        @GetMapping("/test/not-found")
        public void notFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "test/not-found");
        }

        @GetMapping("/test/system")
        public void system() {
            throw new RuntimeException("unexpected");
        }
    }
}
