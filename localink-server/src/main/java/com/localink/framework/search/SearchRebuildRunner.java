package com.localink.framework.search;

import com.localink.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * ES 全量重建触发器（B-9）：rebuildAll 原只有测试调用方——增量同步消息丢失后生产无修复通道。
 * 不开 HTTP 管理端点（deploy.md §7 防误触发的既定决策），改为启动期开关：
 * localink.search.rebuild-on-start=true 时启动全量重灌（DB 是事实源，重算幂等）。
 * 运维口径：怀疑 ES 漂移时以该开关滚动重启一次即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchRebuildRunner implements ApplicationRunner {

    private final PostSearchService postSearchService;
    @Value("${localink.search.rebuild-on-start:false}")
    private boolean rebuildOnStart;

    @Override
    public void run(ApplicationArguments args) {
        if (!rebuildOnStart) {
            return;
        }
        long indexed = postSearchService.rebuildAll();
        log.info("ES 启动全量重建完成, indexed={}（rebuild-on-start 已执行, 记得关闭该开关）", indexed);
    }
}
