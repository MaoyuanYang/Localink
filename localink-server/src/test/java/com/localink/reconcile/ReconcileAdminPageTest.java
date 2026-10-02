package com.localink.reconcile;

import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.RollbackFailureLog;
import com.localink.entity.User;
import com.localink.entity.VoucherReconcileLog;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.mapper.RollbackFailureLogMapper;
import com.localink.mapper.UserMapper;
import com.localink.mapper.VoucherReconcileLogMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3 对账看板两端点：流水筛选分页/失败表/匿名拒绝。默认共享上下文。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconcileAdminPageTest {

    private static final String PHONE = "13900139062";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VoucherReconcileLogMapper reconcileLogMapper;

    @Autowired
    private RollbackFailureLogMapper rollbackFailureLogMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<Long> createdLogIds = new ArrayList<>();
    private final List<Long> createdFailureIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdLogIds.forEach(reconcileLogMapper::deleteById);
        createdFailureIds.forEach(rollbackFailureLogMapper::deleteById);
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getPhone, PHONE));
        if (user != null) {
            userMapper.deleteById(user.getId());
        }
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
    }

    @Test
    void pageLogsFiltersByStatus() throws Exception {
        ensureUser();
        Long pendingId = insertLog(1);
        Long consistentId = insertLog(4);

        mockMvc.perform(get("/api/reconcile/admin/page").param("status", "1")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].id").value(String.valueOf(pendingId)))
                .andExpect(jsonPath("$.data.records[0].reconciliationStatus").value(1))
                .andExpect(jsonPath("$.data.records[0].createTime").isNotEmpty());

        mockMvc.perform(get("/api/reconcile/admin/page")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].id").value(String.valueOf(consistentId)))
                .andExpect(jsonPath("$.data.records[0].reconciliationStatus").value(4));
    }

    @Test
    void failuresPageReturnsRows() throws Exception {
        ensureUser();
        Long failureId = insertFailure();

        mockMvc.perform(get("/api/reconcile/admin/failures")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].id").value(String.valueOf(failureId)))
                .andExpect(jsonPath("$.data.records[0].retryAttempts").value(2))
                .andExpect(jsonPath("$.data.records[0].source").value("ORDER_CLOSE"));
    }

    @Test
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/api/reconcile/admin/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(get("/api/reconcile/admin/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
    }

    private void ensureUser() {
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getPhone, PHONE));
        if (user == null) {
            User u = new User();
            u.setPhone(PHONE);
            u.setNickName("W3对账测试用户");
            u.setLevel(0);
            userMapper.insert(u);
        }
    }

    private Long insertLog(int status) {
        VoucherReconcileLog log = new VoucherReconcileLog();
        log.setOrderId(System.nanoTime());
        log.setUserId(1L);
        log.setVoucherId(1L);
        log.setTraceId(System.nanoTime());
        log.setLogType(1);
        log.setBusinessType(1);
        log.setBeforeQty(10);
        log.setChangeQty(1);
        log.setAfterQty(9);
        log.setReconciliationStatus(status);
        log.setDetail("W3 测试流水");
        log.setCreateTime(LocalDateTime.now().withNano(0));
        reconcileLogMapper.insert(log);
        createdLogIds.add(log.getId());
        return log.getId();
    }

    private Long insertFailure() {
        RollbackFailureLog log = new RollbackFailureLog();
        log.setVoucherId(1L);
        log.setUserId(1L);
        log.setTraceId(System.nanoTime());
        log.setResultCode(500);
        log.setRetryAttempts(2);
        log.setSource("ORDER_CLOSE");
        log.setDetail("W3 测试失败行");
        log.setCreateTime(LocalDateTime.now().withNano(0));
        rollbackFailureLogMapper.insert(log);
        createdFailureIds.add(log.getId());
        return log.getId();
    }

    private String loginAndGetToken() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        return token;
    }
}
