package com.localink.cache;

/**
 * Redis BitMap 结构操作（M6-F 扩组，m2-1 预留演进点：只增分组不动主接口）。
 * 位下标从 0 起；签到等"按天占位"场景约定今天在最低位。
 */
public interface RedisBitMapOps {

    /**
     * 置位/复位某位（SETBIT），幂等。
     */
    void setBit(KeyBuild key, long offset, boolean value);

    /**
     * 读取某位（GETBIT）；key 不存在返回 false。
     */
    boolean getBit(KeyBuild key, long offset);

    /**
     * 统计为 1 的位数（BITCOUNT）；key 不存在返回 0。
     */
    long bitCount(KeyBuild key);

    /**
     * 无符号位段读取（BITFIELD GET u{bits} {offset}）：从 offset 位起取 bits 位拼成无符号数。
     * 签到连续天数用它一次取整月位串，避免逐位 GETBIT 的 N 次往返。bits 上限 63（long）。
     */
    long getUnsigned(KeyBuild key, int bits, long offset);
}
