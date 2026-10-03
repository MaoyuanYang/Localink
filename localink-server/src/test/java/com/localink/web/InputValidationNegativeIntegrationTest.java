package com.localink.web;

import com.localink.common.code.BaseCode;
import com.localink.framework.holder.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 输入校验负面矩阵（2026-10-02）：对公共读端点与登录前可达端点做非法输入扫描。
 * 断言口径：业务异常一律 HTTP 200 + 业务码；非法参数不允许引发未处理 500。
 * T2 修订：page 类型不匹配已修为 40001（F-2）；分页钳制复核为一直存在（F-3 勘误，
 * size 钳 1..50、page 钳 ≥1 由各 Service safePage 实现）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InputValidationNegativeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void cleanup() {
        UserHolder.clear();
    }

    @Test
    void nonexistentShopReturnsNotFoundCode() throws Exception {
        mockMvc.perform(get("/api/shop/424242"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.NOT_FOUND.getCode()));
    }

    @Test
    void nonexistentPostDetailReturnsNotFoundCode() throws Exception {
        mockMvc.perform(get("/api/post/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.NOT_FOUND.getCode()));
    }

    @Test
    void nonexistentVoucherTokenApplyRejectedBeforeLogin() throws Exception {
        mockMvc.perform(post("/api/seckill-voucher/999999/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void malformedJsonBodyReturnsParamError() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void missingRequiredRequestParamReturnsParamError() throws Exception {
        mockMvc.perform(get("/api/order/admin/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void emptyBodyLoginReturnsParamError() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void nonexistentRouteReturnsHttp404WithNotFoundCode() throws Exception {
        mockMvc.perform(get("/api/no/such/route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(BaseCode.NOT_FOUND.getCode()));
    }

    @Test
    void pageParamsAreClampedToSafeRange() throws Exception {
        // T2 复核 F-3（T1 报告勘误）：size 上界钳 50、负/零值钳 1、page<1 钳 1——
        // 钳制实现在各 Service 的 safePage（M8 B-19 修复），T1 误判"全量返回"
        mockMvc.perform(get("/api/shop/page?size=100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()")
                        .value(org.hamcrest.Matchers.lessThanOrEqualTo(50)));
        mockMvc.perform(get("/api/shop/page?size=-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()")
                        .value(org.hamcrest.Matchers.lessThanOrEqualTo(1)));
        mockMvc.perform(get("/api/shop/page?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1));
    }

    @Test
    void malformedPageParamTypeReturnsParamError() throws Exception {
        mockMvc.perform(get("/api/shop/page?page=abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }
}
