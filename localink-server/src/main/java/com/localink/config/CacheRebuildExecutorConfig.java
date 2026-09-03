package com.localink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 缓存重建专用线程池：逻辑过期命中后异步查库回填。
 * 拒绝策略 CallerRuns：极端堆积时退化为调用线程同步重建（等效回退互斥锁方案），任务内 finally 释锁不受影响。
 */
@Configuration
public class CacheRebuildExecutorConfig {

    @Bean("cacheRebuildExecutor")
    public ThreadPoolTaskExecutor cacheRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("cache-rebuild-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
