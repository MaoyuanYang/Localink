package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
import com.localink.cache.RedisGeoOps;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 StringRedisTemplate 的 GEO 默认实现（search 用 3.5 的 GeoReference API，
 * radius 系列已 @Deprecated）。
 */
@RequiredArgsConstructor
public class DefaultRedisGeoOps implements RedisGeoOps {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void add(KeyBuild key, double longitude, double latitude, String member) {
        redisTemplate.opsForGeo().add(key.getKey(), new Point(longitude, latitude), member);
    }

    @Override
    public void remove(KeyBuild key, String... members) {
        redisTemplate.opsForGeo().remove(key.getKey(), members);
    }

    @Override
    public List<GeoEntry> search(KeyBuild key, double longitude, double latitude,
                                 double radiusMeters, int count) {
        GeoReference<String> reference = GeoReference.fromCoordinate(longitude, latitude);
        RedisGeoCommands.GeoSearchCommandArgs args =
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(count);
        // 当前 spring-data-commons 的 Metrics 无 METERS 枚举——以千米查询，结果换算米
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().search(key.getKey(), reference,
                        new Distance(radiusMeters / 1000.0, Metrics.KILOMETERS), args);
        List<GeoEntry> entries = new ArrayList<>();
        if (results == null) {
            return entries;
        }
        results.forEach(result -> entries.add(new GeoEntry(
                result.getContent().getName(),
                result.getDistance().getValue() * 1000)));
        return entries;
    }
}
