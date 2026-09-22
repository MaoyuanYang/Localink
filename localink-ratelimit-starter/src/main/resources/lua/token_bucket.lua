-- 令牌桶限流（单脚本原子：补充+判定+扣减+续期）
-- KEYS[1] 桶 key（Hash：tokens=当前令牌数浮点 / last=上次补充时刻毫秒）
-- ARGV[1] capacity 桶容量   ARGV[2] rate 补充速率（个/秒）   ARGV[3] permits 本次申请
-- ARGV[4] now_ms 当前毫秒（Java 传入，Lua 内 TIME 属非确定性命令）   ARGV[5] ttl_sec 空闲清理秒数
-- 返回 '1|remaining' 放行 / '0|remaining' 拒绝（remaining=判定后可用令牌，向下取整）
-- 关键点：拒绝分支同样推进 last 并落 tokens——上次对齐点不前进会重复计息；补充封顶 capacity 防空闲后突发透支
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'last')
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local tokens = tonumber(bucket[1])
local last = tonumber(bucket[2])
if tokens == nil then
    tokens = capacity
    last = now
end
local elapsed = math.max(0, now - last)
tokens = math.min(capacity, tokens + elapsed * rate / 1000)
local allowed = 0
if tokens >= permits then
    tokens = tokens - permits
    allowed = 1
end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'last', now)
redis.call('EXPIRE', KEYS[1], ARGV[5])
return allowed .. '|' .. math.floor(tokens)
