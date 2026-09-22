-- 秒杀前置令牌一次性消费（原子 GET+DEL：校验与删除无并发缝隙）
-- KEYS[1] 令牌 key（String，值=随机令牌）
-- ARGV[1] 请求携带的令牌值
-- 返回：0 消费通过 / 1 令牌不存在（未申请或已过期或已消费）/ 2 令牌不匹配
-- 不匹配同样删除（一次性语义，防猜测重放）；消费通过即删——同令牌第二次必拒
local stored = redis.call('GET', KEYS[1])
if not stored then
    return 1
end
redis.call('DEL', KEYS[1])
if stored == ARGV[1] then
    return 0
end
return 2
