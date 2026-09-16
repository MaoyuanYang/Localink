package com.localink.lock.test;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ServiceLockAspectIntegrationTest {

    private static final String ORDER_KEY = "lk:lock:test-order:123";
    private static final String[] RESIDUE_KEYS = {ORDER_KEY, "lk:lock:test-gate:shared", "lk:lock:test-slow:k"};

    @Autowired
    private AnnotatedLockService annotatedLockService;

    @Autowired
    private RedissonClient redissonClient;

    @AfterEach
    void cleanup() {
        for (String key : RESIDUE_KEYS) {
            RLock lock = redissonClient.getLock(key);
            if (lock.isLocked()) {
                lock.forceUnlock();
            }
        }
    }

    @Test
    void spelKeyResolvesToDocumentedRedisKey() throws Exception {
        // 阻塞锁必须由其他线程持有：切面在调用线程上获取锁，同线程会因可重入直接成功
        RLock blocker = redissonClient.getLock(ORDER_KEY);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> {
                blocker.lock();
                try {
                    held.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("阻塞锁等待释放超时");
                    }
                } finally {
                    blocker.unlock();
                }
                return null;
            });
            assertTrue(held.await(5, TimeUnit.SECONDS));

            LocalinkException rejected = assertThrows(LocalinkException.class,
                    () -> annotatedLockService.process("123"));
            assertEquals(BaseCode.LOCK_TIMEOUT.getCode(), rejected.getCode());

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("ok:123", annotatedLockService.process("123"));
    }

    @Test
    void concurrentCallsOnSameKeyAreSerialized() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    annotatedLockService.gate("shared");
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads, annotatedLockService.totalEntered());
        assertEquals(1, annotatedLockService.maxConcurrent(), "同 key 并发调用应被串行化");
    }

    @Test
    void waitTimeExhaustionThrowsLockTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> {
                annotatedLockService.slowHold("k", entered, release);
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            LocalinkException rejected = assertThrows(LocalinkException.class,
                    () -> annotatedLockService.slowHold("k", entered, release));
            assertEquals(BaseCode.LOCK_TIMEOUT.getCode(), rejected.getCode());

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
