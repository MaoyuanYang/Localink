-- 机器位轮转分配（M4.2）：INCR 原子取号 + 取模轮转，全周期后回到 0
-- KEYS[1] 分配序号 key（String 计数器）   KEYS[2] 占用登记 key（Hash，field=workId，value=实例标识）
-- ARGV[1] 位宽上限（31，即 5 bit workId）   ARGV[2] 实例标识（host:pid）   ARGV[3] key TTL 秒
-- 返回：分配到的 workId（0-31）
-- 局限（任务卡声明）：轮转不保证独占——同 workId 被两实例同时持有的窗口存在于实例数 > 上限或重启时序中；
-- 占用登记仅供观测排障，不做强校验。生产演进方向：租约 + 心跳续期 + 冲突重试（ZooKeeper/Redisson 级语义）
local seq = redis.call('INCR', KEYS[1])
local workId = seq % (tonumber(ARGV[1]) + 1)
redis.call('HSET', KEYS[2], workId, ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
redis.call('EXPIRE', KEYS[2], ARGV[3])
return workId
