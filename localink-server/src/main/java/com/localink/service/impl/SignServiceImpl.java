package com.localink.service.impl;

import com.localink.api.vo.SignVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.framework.holder.UserHolder;
import com.localink.service.SignService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 签到实现（M6-F）：BitMap 按月（user:sign:{userId}:{yyyyMM}），第 dayOfMonth-1 位=当天，
 * 今天在最低位。连续签到=BITFIELD 取"本月 1 日至今"位串从最低位向上数连续 1，
 * 位串顶满（本月天天签）时跨月续查上月——跨天无需迁移，跨月自然换 key。
 * 一人一月 31 位=4 字节；SETBIT 幂等；TTL 62 天由签到动作显式续期。
 */
@Service
@RequiredArgsConstructor
public class SignServiceImpl implements SignService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    public SignVO checkIn() {
        Long userId = UserHolder.get().getId();
        LocalDate today = LocalDate.now();
        KeyBuild key = signKey(userId, today);
        redisCache.bitmaps().setBit(key, today.getDayOfMonth() - 1, true);
        redisCache.expire(key, Duration.ofDays(62));
        return status();
    }

    @Override
    public SignVO status() {
        Long userId = UserHolder.get().getId();
        LocalDate today = LocalDate.now();
        KeyBuild key = signKey(userId, today);
        SignVO vo = new SignVO();
        vo.setSignedToday(redisCache.bitmaps().getBit(key, today.getDayOfMonth() - 1));
        vo.setMonthDays((int) redisCache.bitmaps().bitCount(key));
        vo.setContinuousDays(continuousDays(userId, today));
        return vo;
    }

    /**
     * 连续签到天数。starter 的 getUnsigned 已翻正为 SETBIT 位序（day 1=bit0，今天/月末=
     * 最高有效位）——从最高位向下数连续 1 即"从今天往回数"；位串顶满（月月天天签）则
     * 续查上月，循环直到出现第一个 0。当前月只读 1 日至今（width=今天日期）。
     */
    private int continuousDays(Long userId, LocalDate today) {
        int continuous = 0;
        LocalDate month = today;
        boolean current = true;
        while (true) {
            int width = current ? today.getDayOfMonth() : month.lengthOfMonth();
            long value = redisCache.bitmaps().getUnsigned(signKey(userId, month), width, 0);
            int counted = 0;
            for (int bit = width - 1; bit >= 0; bit--) {
                if ((value & (1L << bit)) == 0) {
                    break;
                }
                counted++;
            }
            continuous += counted;
            if (counted < width) {
                return continuous;
            }
            month = month.minusMonths(1);
            current = false;
        }
    }

    private KeyBuild signKey(Long userId, LocalDate month) {
        return keyBuilder.build(KeyManage.USER_SIGN, userId, month.format(MONTH));
    }
}
