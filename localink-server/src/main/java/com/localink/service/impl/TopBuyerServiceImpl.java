package com.localink.service.impl;

import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.model.ZSetEntry;
import com.localink.constant.KeyManage;
import com.localink.service.TopBuyerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top 买家实现：按日 ZSet（member=userId，score=当日单数），ZINCRBY 天然并发安全；
 * TTL 2 天过期自清——跨日即新榜（"每日"语义由 key 的日期段承载）。
 */
@Service
@RequiredArgsConstructor
public class TopBuyerServiceImpl implements TopBuyerService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    public void recordOrder(Long shopId, Long userId) {
        String today = LocalDate.now().format(DATE);
        redisCache.zsets().incrementScore(
                keyBuilder.build(KeyManage.SHOP_TOP_BUYERS, shopId, today), String.valueOf(userId), 1);
    }

    @Override
    public List<Map<String, Object>> topBuyers(Long shopId, String date, int limit) {
        String day = date != null ? date : LocalDate.now().format(DATE);
        Set<ZSetEntry<String>> entries = redisCache.zsets().reverseRangeWithScore(
                keyBuilder.build(KeyManage.SHOP_TOP_BUYERS, shopId, day), 0, limit - 1, String.class);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ZSetEntry<String> entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", Long.valueOf(entry.value()));
            row.put("count", (long) entry.score());
            result.add(row);
        }
        return result;
    }
}
