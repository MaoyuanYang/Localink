package com.localink.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

/**
 * 白名单/封禁运营门面（M3.14）：名单存 Redis Set，动态生效免发版。
 * 白名单优先级最高（跳过一切限流），封禁次之（直接拒绝）；静态白名单（yml）与之并集。
 * key：lk:rl:whitelist / lk:rl:banned（KeyManage.RATELIMIT_STATE 登记文档对齐）。
 */
@RequiredArgsConstructor
public class RateLimitAdmin {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    private String whitelistKey() {
        return keyPrefix + "rl:whitelist";
    }

    private String bannedKey() {
        return keyPrefix + "rl:banned";
    }

    public void whitelist(String ip) {
        redisTemplate.opsForSet().add(whitelistKey(), ip);
    }

    public void dewhitelist(String ip) {
        redisTemplate.opsForSet().remove(whitelistKey(), ip);
    }

    public void ban(String ip) {
        redisTemplate.opsForSet().add(bannedKey(), ip);
    }

    public void unban(String ip) {
        redisTemplate.opsForSet().remove(bannedKey(), ip);
    }

    public Set<String> whitelisted() {
        return redisTemplate.opsForSet().members(whitelistKey());
    }

    public Set<String> banned() {
        return redisTemplate.opsForSet().members(bannedKey());
    }

    public boolean isWhitelisted(String ip) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(whitelistKey(), ip));
    }

    public boolean isBanned(String ip) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(bannedKey(), ip));
    }
}
