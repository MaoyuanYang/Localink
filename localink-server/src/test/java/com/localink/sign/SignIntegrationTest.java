package com.localink.sign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.User;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M6-F 签到验证：首签与幂等、连续签到（构造历史位）、断签重计、跨月连续（上月全签+本月全签）。
 * BitMap 语义：第 dayOfMonth-1 位=当天，今天在最低位。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignIntegrationTest {

    private static final String PHONE = "13900139611";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<String> issuedTokens = new ArrayList<>();
    private String token;
    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        token = userService.login(PHONE, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userId = user.getId();
    }

    @AfterEach
    void cleanup() {
        LocalDate month = LocalDate.now();
        for (int i = 0; i < 2; i++) {
            redisCache.delete(signKey(month));
            month = month.minusMonths(1);
        }
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void firstCheckInCountsOneAndIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/user/sign").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.signedToday").value(true))
                .andExpect(jsonPath("$.data.continuousDays").value(1))
                .andExpect(jsonPath("$.data.monthDays").value(1));
        // 重复签到幂等
        mockMvc.perform(post("/api/user/sign").header("Authorization", token))
                .andExpect(jsonPath("$.data.continuousDays").value(1))
                .andExpect(jsonPath("$.data.monthDays").value(1));
    }

    @Test
    void brokenStreakResetsCounting() throws Exception {
        int today = LocalDate.now().getDayOfMonth();
        Assumptions.assumeTrue(today >= 3, "月初 1/2 号无法在本月构造前天位");
        // 只签过前天（today-2 位），昨天未签——今天签到后连续=1（断签重计）
        if (today >= 3) {
            redisCache.bitmaps().setBit(signKey(LocalDate.now()), today - 3, true);
        }
        mockMvc.perform(post("/api/user/sign").header("Authorization", token))
                .andExpect(jsonPath("$.data.continuousDays").value(1))
                .andExpect(jsonPath("$.data.monthDays").value(2));
    }

    @Test
    void continuousDaysCountBackwardsIncludingToday() throws Exception {
        int today = LocalDate.now().getDayOfMonth();
        Assumptions.assumeTrue(today >= 3, "月初 1/2 号无法在本月构造昨天前天位");
        // 昨天与前天都已签
        redisCache.bitmaps().setBit(signKey(LocalDate.now()), today - 2, true);
        redisCache.bitmaps().setBit(signKey(LocalDate.now()), today - 3, true);
        mockMvc.perform(post("/api/user/sign").header("Authorization", token))
                .andExpect(jsonPath("$.data.continuousDays").value(3));
    }

    @Test
    void crossMonthStreakContinuesIntoPreviousMonth() throws Exception {
        int today = LocalDate.now().getDayOfMonth();
        // 本月 1 日至昨天全签 + 今天（签到动作）→ 本月位串顶满，跨月续查
        for (int day = 1; day <= today - 1; day++) {
            redisCache.bitmaps().setBit(signKey(LocalDate.now()), day - 1, true);
        }
        // 上月最后 3 天已签——按上月实际长度置位（W3 排障：硬编码位 28/29/30 假设 31 天大月，
        // 10 月初遇 9 月 30 天时位 30 是不存在日期，跨月续查只连上 2 天）
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        int lastLen = lastMonth.lengthOfMonth();
        redisCache.bitmaps().setBit(signKey(lastMonth), lastLen - 3, true);
        redisCache.bitmaps().setBit(signKey(lastMonth), lastLen - 2, true);
        redisCache.bitmaps().setBit(signKey(lastMonth), lastLen - 1, true);

        mockMvc.perform(post("/api/user/sign").header("Authorization", token))
                .andExpect(jsonPath("$.data.continuousDays").value(today + 3))
                .andExpect(jsonPath("$.data.monthDays").value(today));
    }

    private com.localink.cache.KeyBuild signKey(LocalDate month) {
        return keyBuilder.build(KeyManage.USER_SIGN, userId,
                month.format(DateTimeFormatter.ofPattern("yyyyMM")));
    }
}
