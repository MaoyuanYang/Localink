package com.localink.idempotent.test;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class RepeatExecuteLimitIntegrationTest {

    @Autowired
    private IdempotentService idempotentService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanup() {
        List<String> keys = new ArrayList<>(redisTemplate.keys("lk:idem:*"));
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void sequentialRepeatReplaysStoredResult() {
        int before = idempotentService.returningCount();
        String first = idempotentService.returning("a");
        String second = idempotentService.returning("a");

        assertEquals("v-a", first);
        assertEquals("v-a", second, "重复调用应回放已存结果");
        assertEquals(before + 1, idempotentService.returningCount(), "业务应只执行一次");
    }

    @Test
    void concurrentSameKeyExecutesExactlyOnce() throws Exception {
        int before = idempotentService.returningCount();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> idempotentService.returning("b")));
            }
            for (Future<String> future : futures) {
                assertEquals("v-b", future.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(before + 1, idempotentService.returningCount(), "三级防护下并发同 key 应恰好执行一次");
    }

    @Test
    void failureLeavesNoMarkerAndRetryExecutes() {
        assertThrows(IllegalStateException.class, () -> idempotentService.failFirst("c"));
        assertEquals(1, idempotentService.failCount());

        String retried = idempotentService.failFirst("c");
        assertEquals("ok-c", retried, "失败不落标记，重试应真正执行");
        assertEquals(2, idempotentService.failCount());

        assertEquals("ok-c", idempotentService.failFirst("c"), "成功后标记生效");
        assertEquals(2, idempotentService.failCount());
    }

    @Test
    void distinctKeysExecuteIndependently() {
        int before = idempotentService.returningCount();
        String d1 = idempotentService.returning("d1");
        String d2 = idempotentService.returning("d2");

        assertEquals("v-d1", d1);
        assertEquals("v-d2", d2);
        assertEquals(before + 2, idempotentService.returningCount());
    }

    @Test
    void rejectModeThrowsOnDuplicate() {
        assertEquals("r-e", idempotentService.rejectMode("e"));

        LocalinkException rejected = assertThrows(LocalinkException.class,
                () -> idempotentService.rejectMode("e"));
        assertEquals(BaseCode.IDEMPOTENT_DUPLICATE.getCode(), rejected.getCode());
    }

    @Test
    void voidMethodSkipsSilentlyAndNoMarkerModeRepeats() {
        idempotentService.voidMethod("f");
        idempotentService.voidMethod("f");
        assertEquals(1, idempotentService.voidCount(), "void 重复应静默跳过");

        idempotentService.noMarker("g");
        idempotentService.noMarker("g");
        assertEquals(2, idempotentService.noMarkerCount(), "markerTtl=0 只挡并发不挡重复");
    }
}
