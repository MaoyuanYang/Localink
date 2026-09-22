-- 秒杀 DB 建单失败后的 Redis 补偿：库存加回 + 已购集合移除 + 流水翻恢复态（幂等，可安全重试）
-- KEYS[1] 库存 key   KEYS[2] 已购用户集合 key   KEYS[3] 流水 key（Hash）——同 hash tag 槽位
-- ARGV[1] 用户 ID   ARGV[2] traceId（与扣减流水同一资格生命周期 ID，可空串=不翻流水）
-- ARGV[3] 流水时间戳（epoch 毫秒）
-- 返回字符串：'0|before|after' 补偿完成 / '1' 无需补偿（用户不在集合中）
-- 逆增量而非 DEL 回源：本项目懒回源属 M3.11/M3.12 对账体系，DEL 后无人重建等于全挂
-- 流水仅在 key 仍存活时翻新（活动 TTL 已过则 Redis 活账本整体清空，长账在 DB 恢复行）
local inSet = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if inSet == 0 then
    return '1'
end
local before = 0
if redis.call('EXISTS', KEYS[1]) == 1 then
    before = tonumber(redis.call('GET', KEYS[1]))
    redis.call('INCRBY', KEYS[1], 1)
end
redis.call('SREM', KEYS[2], ARGV[1])
if ARGV[2] ~= '' and redis.call('EXISTS', KEYS[3]) == 1 then
    redis.call('HSET', KEYS[3], ARGV[2],
            '{"traceId":' .. ARGV[2] .. ',"userId":' .. ARGV[1]
                    .. ',"logType":2,"beforeQty":' .. before
                    .. ',"changeQty":1,"afterQty":' .. (before + 1)
                    .. ',"ts":' .. ARGV[3] .. '}')
end
return '0|' .. before .. '|' .. (before + 1)
