-- 秒杀资格判定 + 原子扣减 + 对账流水（单脚本原子，Redis 单线程执行无并发缝隙）
-- KEYS[1] 库存 key（String，纯数字）  KEYS[2] 已购用户集合 key（Set）  KEYS[3] 流水 key（Hash）——三 key 须同 hash tag 槽位
-- ARGV[1] 用户 ID   ARGV[2] 活动剩余秒数（扣减后刷新三 key 的 TTL，活动结束自动清理）
-- ARGV[3] traceId（资格生命周期 ID，Java 预生成雪花）   ARGV[4] 流水时间戳（epoch 毫秒）
-- 返回字符串：'0|traceId|before|after' 扣减成功 / '1' 库存未预热 / '2' 库存不足 / '3' 重复购买
-- M3.12：扣减与流水同脚本落账——对账要回答"Redis 扣了谁"，账本必须在扣减的同一原子动作里写
local stock = redis.call('GET', KEYS[1])
if not stock then
    return '1'
end
if tonumber(stock) <= 0 then
    return '2'
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return '3'
end
local before = tonumber(stock)
local after = before - 1
redis.call('INCRBY', KEYS[1], -1)
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[3],
        '{"traceId":' .. ARGV[3] .. ',"userId":' .. ARGV[1]
                .. ',"logType":1,"beforeQty":' .. before
                .. ',"changeQty":-1,"afterQty":' .. after
                .. ',"ts":' .. ARGV[4] .. '}')
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('EXPIRE', KEYS[2], ARGV[2])
redis.call('EXPIRE', KEYS[3], ARGV[2])
return '0|' .. ARGV[3] .. '|' .. before .. '|' .. after
