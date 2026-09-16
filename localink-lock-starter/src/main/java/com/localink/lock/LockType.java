package com.localink.lock;

/**
 * 分布式锁类型。READ 与 WRITE 共用同一 key 上的一把读写锁：读读共享、读写互斥、写写互斥。
 */
public enum LockType {

    /** 可重入锁（默认）：同线程可重复获取，按持有计数逐层释放。 */
    REENTRANT,

    /** 公平锁：按请求到达顺序排队获取，公平但吞吐低于可重入锁。 */
    FAIR,

    /** 读锁：同 key 多个读锁并发共享，与写锁互斥。 */
    READ,

    /** 写锁：同 key 下与读锁、写锁全部互斥。 */
    WRITE
}
