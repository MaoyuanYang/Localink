package com.localink.service;

import java.util.List;
import java.util.Map;

/**
 * 店铺每日 Top 买家（M5-C）：建单事务内 ZINCRBY 维护、按日 key 自清（database.md 第 7 节"由订单重算"口径）。
 */
public interface TopBuyerService {

    /**
     * 建单成功后记账（建单事务内调用）：shop:top:{shopId}:{yyyyMMdd} 对 userId +1。
     */
    void recordOrder(Long shopId, Long userId);

    /**
     * 指定日期的 Top N 买家（userId → 当日单数，降序）。date 格式 yyyyMMdd，null=今日。
     */
    List<Map<String, Object>> topBuyers(Long shopId, String date, int limit);
}
