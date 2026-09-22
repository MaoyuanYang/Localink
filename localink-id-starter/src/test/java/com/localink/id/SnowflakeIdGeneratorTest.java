package com.localink.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4.1 雪花算法验证：位账、趋势递增、并发唯一、时钟回拨的等待与拒绝。
 * 时间源可注入——回拨行为用受控时钟复现，不真改系统时间。
 */
class SnowflakeIdGeneratorTest {

    @Test
    void idCarriesConfiguredMachineBits() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(7, 3);
        long id = generator.nextId();

        long sequence = id & 4095;
        long workId = (id >> 12) & 31;
        long dataCenterId = (id >> 17) & 31;
        long timestamp = (id >> 22) + SnowflakeIdGenerator.EPOCH;

        assertEquals(7, workId, "workId 位账");
        assertEquals(3, dataCenterId, "dataCenterId 位账");
        assertEquals(0, sequence, "新毫秒首号序列为 0");
        assertTrue(Math.abs(timestamp - System.currentTimeMillis()) < 10_000, "时间戳段应近当前时刻");
    }

    @Test
    void idsAreMonotonicPerGenerator() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        long prev = generator.nextId();
        for (int i = 0; i < 10_000; i++) {
            long current = generator.nextId();
            assertTrue(current > prev, "同生成器 ID 趋势递增");
            prev = current;
        }
    }

    @Test
    void concurrentGenerationAcrossInstancesStaysUnique() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        Set<Long> all = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            long workId = t;
            pool.submit(() -> {
                try {
                    SnowflakeIdGenerator generator = new SnowflakeIdGenerator(workId, 0);
                    for (int i = 0; i < perThread; i++) {
                        all.add(generator.nextId());
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(threads * perThread, all.size(), "8 实例 4 万号零重复（机器位隔离 + 序列原子）");
    }

    @Test
    void sequenceExhaustionRollsToNextMillisecond() {
        // 真实时钟：单线程 4097 号大概率同毫秒耗尽序列后翻毫秒（自旋至多等 1ms）；
        // 固定时钟会令 tilNextMillis 永久自旋——时间必须前进是雪花算法的前提
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4097; i++) {
            ids.add(generator.nextId());
        }
        assertEquals(4097, ids.size(), "同毫秒 4096 号耗尽后翻毫秒，不丢号不重复");
    }

    @Test
    void smallClockBackwardWaitsForTimeToCatchUp() {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, 5, clock::get);
        generator.nextId();

        clock.set(clock.get() - 3);
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            clock.set(System.currentTimeMillis());
        });
        watcher.start();
        long id = generator.nextId();
        assertTrue(id > 0, "3ms 小回拨应自旋等待追平后正常发号");
    }

    @Test
    void largeClockBackwardRefusesToGenerate() {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, 5, clock::get);
        generator.nextId();

        clock.set(clock.get() - 100);
        assertThrows(IllegalStateException.class, generator::nextId,
                "100ms 大回拨应拒绝发号（宁可不可用，不冒重复险）");
    }

    @Test
    void constructorRejectsOutOfRangeMachineBits() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 32));
    }
}
