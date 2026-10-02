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
 * 已知取舍：分页参数当前无钳制（size=-5/0/100000 均全量返回）与 page 类型不匹配
 * 落 500 兜底（MethodArgumentTypeMismatchException 无专属 handler）——前者按
 * "不 500"口径断言，后者以钉死现状的方式记录（VERIFICATION F-xx），修复后应改断言 40001。
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
    void oversizedPageParamsDoNotCrashServer() throws Exception {
        mockMvc.perform(get("/api/shop/page?size=100000")).andExpect(status().isOk());
        mockMvc.perform(get("/api/shop/page?page=0&size=0")).andExpect(status().isOk());
        mockMvc.perform(get("/api/shop/page?size=-5")).andExpect(status().isOk());
    }

    /**
     * 钉死现状（体检 finding）：page=abc 类型不匹配当前落入兜底 500 + SYSTEM_ERROR。
     * 修复为 40001 后本用例会失败，届时同步更新断言与 VERIFICATION 记录。
     */
    @Test
    void pageTypeMismatchCurrentlyFallsToSystemErrorDocumentedFinding() throws Exception {
        mockMvc.perform(get("/api/shop/page?page=abc"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(BaseCode.SYSTEM_ERROR.getCode()));
    }
}
