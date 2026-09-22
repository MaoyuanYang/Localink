package com.localink.id;

import java.util.function.LongSupplier;

/**
 * 雪花算法全局 ID 生成器（M4.1）：41 bit 毫秒时间戳（自定义纪元 2026-01-01，可用约 69 年）
 * + 10 bit 机器位（dataCenterId 5 + workId 5，共 1024 实例）+ 12 bit 序列（同毫秒 4096 个）。
 *
 * <p>时钟回拨处理：回拨幅度 ≤ {@code maxBackwardMs}（默认 5ms）自旋等待追平——NTP 微调常态；
 * 超阈值抛 {@link IllegalStateException} 拒绝生成——宁可短暂不可用，绝不冒重复 ID 风险。
 * 时间源经构造器注入（默认系统时钟），回拨行为可测。</p>
 */
public class SnowflakeIdGenerator {

    public static final long EPOCH = 1767225600000L;

    private static final long WORK_ID_BITS = 5L;
    private static final long DATA_CENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORK_ID = ~(-1L << WORK_ID_BITS);
    private static final long MAX_DATA_CENTER_ID = ~(-1L << DATA_CENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORK_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORK_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORK_ID_BITS + DATA_CENTER_ID_BITS;

    private final long workId;
    private final long dataCenterId;
    private final long maxBackwardMs;
    private final LongSupplier timeSource;

    private long lastTimestamp = -1L;
    private long sequence;

    public SnowflakeIdGenerator(long workId, long dataCenterId) {
        this(workId, dataCenterId, 5, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(long workId, long dataCenterId, long maxBackwardMs, LongSupplier timeSource) {
        if (workId < 0 || workId > MAX_WORK_ID) {
            throw new IllegalArgumentException("workId 必须在 [0, " + MAX_WORK_ID + "], 当前=" + workId);
        }
        if (dataCenterId < 0 || dataCenterId > MAX_DATA_CENTER_ID) {
            throw new IllegalArgumentException("dataCenterId 必须在 [0, " + MAX_DATA_CENTER_ID + "], 当前=" + dataCenterId);
        }
        this.workId = workId;
        this.dataCenterId = dataCenterId;
        this.maxBackwardMs = maxBackwardMs;
        this.timeSource = timeSource;
    }

    public synchronized long nextId() {
        long now = timeSource.getAsLong();
        now = resolveBackward(now);
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (dataCenterId << DATA_CENTER_ID_SHIFT)
                | (workId << WORK_ID_SHIFT)
                | sequence;
    }

    /**
     * 时钟回拨处置：小回拨自旋等真实时间追平上次发号时刻；大回拨拒绝发号。
     */
    private long resolveBackward(long now) {
        long backward = lastTimestamp - now;
        if (backward <= 0) {
            return now;
        }
        if (backward > maxBackwardMs) {
            throw new IllegalStateException("时钟回拨 " + backward + "ms 超过容忍阈值 " + maxBackwardMs
                    + "ms，拒绝生成 ID（防重复），请检查 NTP 或手动校时");
        }
        do {
            Thread.onSpinWait();
            now = timeSource.getAsLong();
        } while (now < lastTimestamp);
        return now;
    }

    private long tilNextMillis(long last) {
        long now = timeSource.getAsLong();
        while (now <= last) {
            Thread.onSpinWait();
            now = timeSource.getAsLong();
        }
        return now;
    }

    public long getWorkId() {
        return workId;
    }

    public long getDataCenterId() {
        return dataCenterId;
    }
}
