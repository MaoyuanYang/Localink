-- 秒杀 DB 建单失败后的 Redis 补偿：库存加回 + 已购集合移除（幂等，可安全重试）
-- KEYS[1] 库存 key   KEYS[2] 已购用户集合 key
-- ARGV[1] 用户 ID
-- 返回：0 补偿完成 / 1 无需补偿（用户不在集合中）
-- 逆增量而非 DEL 回源：本项目懒回源属 M3.11/M3.12 对账体系，DEL 后无人重建等于全挂
local inSet = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if inSet == 0 then
    return 1
end
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCRBY', KEYS[1], 1)
end
redis.call('SREM', KEYS[2], ARGV[1])
return 0
