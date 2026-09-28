package com.localink.cache;

/**
 * Redis HyperLogLog 结构操作（M6-E 扩组，m2-1 预留演进点：只增分组不动主接口）。
 * HLL 以约 12KB 固定内存做基数估计（误差约 0.81%），不能列出成员、只能估数。
 */
public interface RedisHyperLogLogOps {

    /**
     * 添加元素（PFADD）。不返回 PFADD 的"基数是否变化"布尔——经 Spring 转换后实测不可信，
     * 调用方一律以 count() 为准（M6-E 实测：新元素首添返回 false 但计数已生效）。
     */
    void add(KeyBuild key, Object... values);

    /**
     * 估计基数（PFCOUNT）；多个 key 时为并集基数。key 不存在返回 0。
     */
    long count(KeyBuild... keys);

    /**
     * 合并多个源 key 到目标 key（PFMERGE）；目标已存在则被覆盖为并集。
     */
    void union(KeyBuild destination, KeyBuild... sourceKeys);
}
