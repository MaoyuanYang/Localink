-- 滑动窗口限流（单脚本原子：清窗外+计数+登记+续期）
-- KEYS[1] 窗口 key（ZSet：member=请求唯一标识，score=时间戳毫秒）
-- ARGV[1] threshold 窗口阈值   ARGV[2] window_ms 窗口长度毫秒
-- ARGV[3] now_ms 当前毫秒   ARGV[4] member 请求唯一标识   ARGV[5] ttl_sec 空闲清理秒数
-- 返回 '1|remaining' 放行（登记本请求后窗口剩余额度）/ '0|0' 拒绝
-- 关键点：拒绝不登记（未放行的请求不占窗口）；先清 < now-window 再计数，score 恰等于窗口起点的保留（闭区间语义）
local window_start = tonumber(ARGV[3]) - tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. window_start)
local count = redis.call('ZCARD', KEYS[1])
local threshold = tonumber(ARGV[1])
if count < threshold then
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
    redis.call('EXPIRE', KEYS[1], ARGV[5])
    return '1|' .. (threshold - count - 1)
end
redis.call('EXPIRE', KEYS[1], ARGV[5])
return '0|0'
