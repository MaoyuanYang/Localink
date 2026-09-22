package com.localink.id;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 机器位轮转分配器（M4.2）：Redis Lua INCR 取号 + 取模轮转（0-31 循环），
 * 多实例启动时各自领号，降低雪花 ID 机器位撞号概率；占用登记（Hash，value=pid@host）供观测排障。
 * 轮转非独占语义与生产演进方向见 allocate_work_id.lua 头注。
 */
@RequiredArgsConstructor
public class WorkIdAllocator {

    private static final long WORK_ID_MAX = 31;
    private static final long KEY_TTL_SECONDS = 86_400;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> allocateWorkIdScript;
    private final String keyPrefix;

    /**
     * 领取一个 workId（0-31 轮转）。
     */
    public long allocate() {
        Long workId = redisTemplate.execute(allocateWorkIdScript,
                List.of(keyPrefix + "id:work:seq", keyPrefix + "id:work:used"),
                String.valueOf(WORK_ID_MAX), ManagementFactory.getRuntimeMXBean().getName(),
                String.valueOf(KEY_TTL_SECONDS));
        if (workId == null) {
            throw new IllegalStateException("workId 分配脚本无返回值");
        }
        return workId;
    }
}
