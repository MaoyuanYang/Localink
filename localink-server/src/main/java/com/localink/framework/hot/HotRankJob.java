package com.localink.framework.hot;

import com.localink.config.HotProperties;
import com.localink.service.HotRankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 热榜快照定时任务（M6-E，ReconciliationJob 同款模板）：fixedDelay 上一轮完成后再计时，
 * 天然防重叠；public 入口在 HotRankService.runOnce()，测试直调不等待调度。
 * 快照失败只告警不抛——热榜是派生视图，下一轮重算自愈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotRankJob {

    private final HotRankService hotRankService;
    private final HotProperties properties;

    @Scheduled(fixedDelayString = "${localink.hot.interval-ms:300000}")
    public void scheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            hotRankService.runOnce();
        } catch (Exception e) {
            log.warn("热榜快照失败（下一轮重算自愈）: {}", e.getMessage());
        }
    }
}
