-- 秒杀资格判定 + 原子扣减（单脚本原子，Redis 单线程执行无并发缝隙）
-- KEYS[1] 库存 key（String，纯数字）  KEYS[2] 已购用户集合 key（Set）——两 key 须同 hash tag 槽位
-- ARGV[1] 用户 ID   ARGV[2] 活动剩余秒数（扣减后刷新两 key 的 TTL，活动结束自动清理）
-- 返回：0 成功 / 1 库存未预热 / 2 库存不足 / 3 重复购买
local stock = redis.call('GET', KEYS[1])
if not stock then
    return 1
end
if tonumber(stock) <= 0 then
    return 2
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 3
end
redis.call('INCRBY', KEYS[1], -1)
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('EXPIRE', KEYS[2], ARGV[2])
return 0
