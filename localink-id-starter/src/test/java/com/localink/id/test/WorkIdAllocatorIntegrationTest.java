package com.localink.id.test;

import com.localink.id.SnowflakeIdGenerator;
import com.localink.id.WorkIdAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4.2 Redis 轮转分配验证：取号连续递增、取模轮转回 0、占用登记可观测、装配出的生成器机器位合法。
 */
@SpringBootTest
class WorkIdAllocatorIntegrationTest {

    @Autowired
    private WorkIdAllocator workIdAllocator;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @AfterEach
    void cleanup() {
        redisTemplate.delete("lk:id:work:seq");
        redisTemplate.delete("lk:id:work:used");
    }

    @Test
    void allocateRotatesThroughRangeAndRegistersOccupancy() {
        // 起点不假设（上下文启动时生成器可能已领过号），验证相对语义：连续递增、取模轮转、全位覆盖
        java.util.Set<Long> allocated = new java.util.HashSet<>();
        long prev = workIdAllocator.allocate();
        allocated.add(prev);
        for (int i = 0; i < 31; i++) {
            long next = workIdAllocator.allocate();
            assertEquals((prev + 1) % 32, next, "轮转应连续递增并取模回绕");
            allocated.add(next);
            prev = next;
        }
        assertEquals(32, allocated.size(), "一个完整周期覆盖 0-31 全部位");
        assertEquals(32, redisTemplate.opsForHash().size("lk:id:work:used"),
                "占用登记应覆盖全部 32 个位");
        assertTrue(redisTemplate.getExpire("lk:id:work:seq") > 0, "计数器 key 应带 TTL");
    }

    @Test
    void assembledGeneratorCarriesAllocatedMachineBits() {
        long workId = snowflakeIdGenerator.getWorkId();
        assertTrue(workId >= 0 && workId <= 31, "装配出的生成器机器位应在合法区间, 实际=" + workId);
        long id = snowflakeIdGenerator.nextId();
        assertEquals(workId, (id >> 12) & 31, "ID 的 workId 段应与分配值一致");
    }
}
