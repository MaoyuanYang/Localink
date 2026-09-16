package com.localink.lock.test;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.lock.DistributedLock;
import com.localink.lock.LockType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DistributedLockIntegrationTest {

    private static final Duration FAST_WAIT = Duration.ofMillis(300);
    private static final Duration PATIENT_WAIT = Duration.ofSeconds(5);

    @Autowired
    private DistributedLock distributedLock;

    @Autowired
    private RedissonClient redissonClient;

    private final List<String> usedKeys = new ArrayList<>();
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        usedKeys.forEach(key -> {
            RLock lock = redissonClient.getLock(key);
            if (lock.isLocked()) {
                lock.forceUnlock();
            }
        });
    }

    @Test
    void reentrantLockSupportsNestedAcquireOnSameThread() {
        String key = track("test:m33:reentrant");
        String result = distributedLock.runWithLock(key, LockType.REENTRANT, PATIENT_WAIT, null, () ->
                distributedLock.runWithLock(key, LockType.REENTRANT, FAST_WAIT, null, () -> "nested-ok"));
        assertEquals("nested-ok", result);
        assertFalse(redissonClient.getLock(key).isLocked());
    }

    @Test
    void mutualExclusionTimesOutThenSucceedsAfterRelease() throws Exception {
        String key = track("test:m33:mutex");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<String> holder = pool.submit(() -> distributedLock.runWithLock(key, LockType.REENTRANT,
                PATIENT_WAIT, null, () -> {
                    held.countDown();
                    await(release);
                    return "holder-done";
                }));
        assertTrue(held.await(5, TimeUnit.SECONDS));

        LocalinkException rejected = assertThrows(LocalinkException.class,
                () -> distributedLock.runWithLock(key, LockType.REENTRANT, FAST_WAIT, null, () -> "second"));
        assertEquals(BaseCode.LOCK_TIMEOUT.getCode(), rejected.getCode());
        assertTrue(redissonClient.getLock(key).isLocked(), "竞争者获取失败不得影响持有者的锁");

        release.countDown();
        assertEquals("holder-done", holder.get(5, TimeUnit.SECONDS));
        assertEquals("second-ok", distributedLock.runWithLock(key, LockType.REENTRANT, PATIENT_WAIT,
                null, () -> "second-ok"));
    }

    @Test
    void fairLockAlsoMutuallyExcludes() throws Exception {
        String key = track("test:m33:fair");
        CountDownLatch held = new CountDownLatch(1);
        Future<Boolean> holder = pool.submit(() -> distributedLock.runWithLock(key, LockType.FAIR,
                PATIENT_WAIT, null, () -> {
                    held.countDown();
                    sleep(800);
                    return true;
                }));
        assertTrue(held.await(5, TimeUnit.SECONDS));

        LocalinkException rejected = assertThrows(LocalinkException.class,
                () -> distributedLock.runWithLock(key, LockType.FAIR, Duration.ofMillis(100), null, () -> true));
        assertEquals(BaseCode.LOCK_TIMEOUT.getCode(), rejected.getCode());
        assertTrue(holder.get(5, TimeUnit.SECONDS));
    }

    @Test
    void readLocksAreSharedBetweenThreads() throws Exception {
        String key = track("test:m33:read-shared");
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<Boolean>> readers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            readers.add(pool.submit(() -> distributedLock.runWithLock(key, LockType.READ,
                    PATIENT_WAIT, null, () -> {
                        bothInside.countDown();
                        await(release);
                        return true;
                    })));
        }
        assertTrue(bothInside.await(3, TimeUnit.SECONDS), "两个读锁应能同时进入临界区");
        release.countDown();
        for (Future<Boolean> reader : readers) {
            assertTrue(reader.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void writeLockExcludesReadLock() throws Exception {
        String key = track("test:m33:write-vs-read");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> writer = pool.submit(() -> distributedLock.runWithLock(key, LockType.WRITE,
                PATIENT_WAIT, null, () -> {
                    held.countDown();
                    await(release);
                    return true;
                }));
        assertTrue(held.await(5, TimeUnit.SECONDS));

        LocalinkException rejected = assertThrows(LocalinkException.class,
                () -> distributedLock.runWithLock(key, LockType.READ, Duration.ofMillis(200), null, () -> true));
        assertEquals(BaseCode.LOCK_TIMEOUT.getCode(), rejected.getCode());

        release.countDown();
        assertTrue(writer.get(5, TimeUnit.SECONDS));
    }

    @Test
    void tryWithLockElectsSingleWorkerAndReturnsEmptyWhenHeld() throws Exception {
        String key = track("test:m35:try-elect");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Optional<String>> worker = pool.submit(() ->
                distributedLock.tryWithLock(key, LockType.REENTRANT, null, () -> {
                    held.countDown();
                    await(release);
                    return "worker";
                }));
        assertTrue(held.await(5, TimeUnit.SECONDS));
        assertTrue(distributedLock.tryWithLock(key, LockType.REENTRANT, null, () -> "intruder").isEmpty(),
                "锁被占用时 tryWithLock 应立即返回 empty");

        release.countDown();
        assertEquals(Optional.of("worker"), worker.get(5, TimeUnit.SECONDS));
        assertTrue(distributedLock.tryWithLock(key, LockType.REENTRANT, null, () -> "next").isPresent(),
                "释放后应可再次获取");
    }

    private String track(String key) {
        usedKeys.add(key);
        return key;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch 等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
