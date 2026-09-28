package com.localink.cache.test;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.model.ZSetEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RedisCacheIntegrationTest {

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private KeyBuild stringKey;
    private KeyBuild stringTtlKey;
    private KeyBuild hashKey;
    private KeyBuild setKeyA;
    private KeyBuild setKeyB;
    private KeyBuild zsetKey;

    @BeforeEach
    void initKeys() {
        stringKey = keyBuilder.build(TestKeys.STRING);
        stringTtlKey = keyBuilder.build(TestKeys.STRING_TTL);
        hashKey = keyBuilder.build(TestKeys.HASH);
        setKeyA = keyBuilder.build(TestKeys.SET_A);
        setKeyB = keyBuilder.build(TestKeys.SET_B);
        zsetKey = keyBuilder.build(TestKeys.ZSET);
    }

    @AfterEach
    void cleanup() {
        redisCache.delete(List.of(stringKey, stringTtlKey, hashKey, setKeyA, setKeyB, zsetKey));
    }

    @Test
    void redisCacheBeanAutoConfigured() {
        assertNotNull(redisCache);
    }

    @Test
    void keyBuilderBeanAutoConfigured() {
        assertNotNull(keyBuilder);
        assertEquals("lk:" + "test:m22:string", keyBuilder.build(TestKeys.STRING).getKey());
    }

    @Test
    void stringSetAndGetTypedObject() {
        CacheTestPayload payload = new CacheTestPayload("tom", 3);
        redisCache.strings().set(stringKey, payload);
        CacheTestPayload cached = redisCache.strings().get(stringKey, CacheTestPayload.class);
        assertEquals(payload, cached);
        String raw = stringRedisTemplate.opsForValue().get(stringKey.getKey());
        assertNotNull(raw);
        assertTrue(raw.startsWith("{"));
    }

    @Test
    void stringSetPlainValueRawRead() {
        redisCache.strings().set(stringKey, "hello");
        assertEquals("hello", redisCache.strings().getString(stringKey));
        assertEquals("hello", redisCache.strings().get(stringKey, String.class));
    }

    @Test
    void stringSetWithTtl() {
        redisCache.strings().set(stringTtlKey, "v", Duration.ofSeconds(60));
        Long expire = redisCache.getExpire(stringTtlKey);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 60);
    }

    @Test
    void stringSetIfAbsent() {
        assertTrue(redisCache.strings().setIfAbsent(stringKey, "first", Duration.ofSeconds(60)));
        assertFalse(redisCache.strings().setIfAbsent(stringKey, "second", Duration.ofSeconds(60)));
        assertEquals("first", redisCache.strings().getString(stringKey));
    }

    @Test
    void stringGetList() {
        List<CacheTestPayload> list = List.of(new CacheTestPayload("a", 1), new CacheTestPayload("b", 2));
        redisCache.strings().set(stringKey, list);
        List<CacheTestPayload> cached = redisCache.strings().getList(stringKey, CacheTestPayload.class);
        assertEquals(list, cached);
    }

    @Test
    void stringGetAndDelete() {
        redisCache.strings().set(stringKey, "once");
        assertEquals("once", redisCache.strings().getAndDelete(stringKey));
        assertFalse(redisCache.hasKey(stringKey));
        assertNull(redisCache.strings().getAndDelete(stringKey));
    }

    @Test
    void stringIncrement() {
        assertEquals(2L, redisCache.strings().increment(stringKey, 2));
        assertEquals(5L, redisCache.strings().increment(stringKey, 3));
    }

    @Test
    void stringGetMissingKeyReturnsNull() {
        assertNull(redisCache.strings().get(stringKey, CacheTestPayload.class));
        assertNull(redisCache.strings().getString(stringKey));
        assertNull(redisCache.strings().getList(stringKey, CacheTestPayload.class));
    }

    @Test
    void hashPutAndGetTyped() {
        CacheTestPayload payload = new CacheTestPayload("jerry", 5);
        redisCache.hashes().put(hashKey, "user", payload);
        CacheTestPayload cached = redisCache.hashes().get(hashKey, "user", CacheTestPayload.class);
        assertEquals(payload, cached);
    }

    @Test
    void hashPutAllAndEntries() {
        Map<String, String> fields = Map.of("id", "1", "name", "tom");
        redisCache.hashes().putAll(hashKey, fields);
        assertEquals(fields, redisCache.hashes().entries(hashKey));
    }

    @Test
    void hashPutAllWithTtl() {
        redisCache.hashes().putAll(hashKey, Map.of("id", "1"), Duration.ofSeconds(60));
        Long expire = redisCache.getExpire(hashKey);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 60);
    }

    @Test
    void hashHasFieldAndDeleteField() {
        redisCache.hashes().put(hashKey, "f1", "v1");
        assertTrue(redisCache.hashes().hasField(hashKey, "f1"));
        assertEquals(1L, redisCache.hashes().delete(hashKey, "f1"));
        assertFalse(redisCache.hashes().hasField(hashKey, "f1"));
    }

    @Test
    void hashIncrementField() {
        assertEquals(3L, redisCache.hashes().increment(hashKey, "counter", 3));
        assertEquals(5L, redisCache.hashes().increment(hashKey, "counter", 2));
    }

    @Test
    void setAddIsMemberSize() {
        assertEquals(3L, redisCache.sets().add(setKeyA, 1L, 2L, 3L));
        assertTrue(redisCache.sets().isMember(setKeyA, 2L));
        assertFalse(redisCache.sets().isMember(setKeyA, 9L));
        assertEquals(3L, redisCache.sets().size(setKeyA));
        assertEquals(1L, redisCache.sets().add(setKeyA, 3L, 4L));
    }

    @Test
    void setRemoveAndMembers() {
        redisCache.sets().add(setKeyA, 1L, 2L, 3L);
        assertEquals(1L, redisCache.sets().remove(setKeyA, 1L));
        Set<Long> members = redisCache.sets().members(setKeyA, Long.class);
        assertEquals(Set.of(2L, 3L), members);
    }

    @Test
    void setIntersect() {
        redisCache.sets().add(setKeyA, 1L, 2L, 3L);
        redisCache.sets().add(setKeyB, 2L, 3L, 4L);
        Set<Long> intersect = redisCache.sets().intersect(setKeyA, setKeyB, Long.class);
        assertEquals(Set.of(2L, 3L), intersect);
    }

    @Test
    void zsetAddScoreSize() {
        assertTrue(redisCache.zsets().add(zsetKey, "a", 1));
        assertTrue(redisCache.zsets().add(zsetKey, "b", 3));
        assertTrue(redisCache.zsets().add(zsetKey, "c", 2));
        assertEquals(3L, redisCache.zsets().size(zsetKey));
        assertEquals(1.0, redisCache.zsets().score(zsetKey, "a"));
        assertFalse(redisCache.zsets().add(zsetKey, "a", 10));
        assertEquals(10.0, redisCache.zsets().score(zsetKey, "a"));
    }

    @Test
    void zsetAddWithTtl() {
        redisCache.zsets().add(zsetKey, "x", 1, Duration.ofSeconds(60));
        Long expire = redisCache.getExpire(zsetKey);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 60);
    }

    @Test
    void zsetIncrementScore() {
        redisCache.zsets().add(zsetKey, "a", 1);
        assertEquals(6.0, redisCache.zsets().incrementScore(zsetKey, "a", 5));
    }

    @Test
    void zsetRankAndReverseRank() {
        seedZset();
        assertEquals(0L, redisCache.zsets().rank(zsetKey, "a"));
        assertEquals(0L, redisCache.zsets().reverseRank(zsetKey, "b"));
        assertNull(redisCache.zsets().rank(zsetKey, "missing"));
    }

    @Test
    void zsetRangeOrdering() {
        seedZset();
        assertEquals(List.of("a", "c", "b"), new ArrayList<>(redisCache.zsets().range(zsetKey, 0, -1, String.class)));
        assertEquals(List.of("b", "c", "a"), new ArrayList<>(redisCache.zsets().reverseRange(zsetKey, 0, -1, String.class)));
    }

    @Test
    void zsetReverseRangeWithScore() {
        seedZset();
        Set<ZSetEntry<String>> entries = redisCache.zsets().reverseRangeWithScore(zsetKey, 0, -1, String.class);
        assertEquals(3, entries.size());
        Iterator<ZSetEntry<String>> it = entries.iterator();
        ZSetEntry<String> first = it.next();
        assertEquals("b", first.value());
        assertEquals(3.0, first.score());
        ZSetEntry<String> second = it.next();
        assertEquals("c", second.value());
        assertEquals(2.0, second.score());
        ZSetEntry<String> third = it.next();
        assertEquals("a", third.value());
        assertEquals(1.0, third.score());
    }

    @Test
    void zsetReverseRangeByScorePagination() {
        seedZset();
        assertEquals(List.of("b"), new ArrayList<>(redisCache.zsets().reverseRangeByScore(zsetKey, 1.5, 10, 0, 1, String.class)));
        assertEquals(List.of("c"), new ArrayList<>(redisCache.zsets().reverseRangeByScore(zsetKey, 1.5, 10, 1, 1, String.class)));
    }

    @Test
    void zsetReverseRangeByScoreWithScoreCarriesScoreForCursor() {
        seedZset();
        Set<ZSetEntry<String>> page = redisCache.zsets().reverseRangeByScoreWithScore(zsetKey, 1.5, 10, 0, 1, String.class);
        assertEquals(1, page.size());
        ZSetEntry<String> entry = page.iterator().next();
        assertEquals("b", entry.value());
        assertEquals(3.0, entry.score());
    }

    @Test
    void zsetRemove() {
        seedZset();
        assertEquals(1L, redisCache.zsets().remove(zsetKey, "a"));
        assertEquals(2L, redisCache.zsets().size(zsetKey));
    }

    @Test
    void hyperloglogDedupCountAndUnion() {
        redisCache.delete(keyBuilder.build(TestKeys.HLL_A));
        redisCache.delete(keyBuilder.build(TestKeys.HLL_B));
        redisCache.delete(keyBuilder.build(TestKeys.HLL_UNION));
        redisCache.hyperloglogs().add(keyBuilder.build(TestKeys.HLL_A), "u1", "u2", "u3");
        assertEquals(3L, redisCache.hyperloglogs().count(keyBuilder.build(TestKeys.HLL_A)));
        redisCache.hyperloglogs().add(keyBuilder.build(TestKeys.HLL_A), "u3");
        assertEquals(3L, redisCache.hyperloglogs().count(keyBuilder.build(TestKeys.HLL_A)), "重复成员基数不变（去重）");
        redisCache.hyperloglogs().add(keyBuilder.build(TestKeys.HLL_B), "u3", "u4");
        assertEquals(4L, redisCache.hyperloglogs().count(
                keyBuilder.build(TestKeys.HLL_A), keyBuilder.build(TestKeys.HLL_B)), "多 key 并集基数");
        redisCache.hyperloglogs().union(keyBuilder.build(TestKeys.HLL_UNION),
                keyBuilder.build(TestKeys.HLL_A), keyBuilder.build(TestKeys.HLL_B));
        assertEquals(4L, redisCache.hyperloglogs().count(keyBuilder.build(TestKeys.HLL_UNION)));
    }

    @Test
    void bitmapSetGetCountAndFieldRead() {
        redisCache.delete(keyBuilder.build(TestKeys.BITMAP));
        redisCache.bitmaps().setBit(keyBuilder.build(TestKeys.BITMAP), 0, true);
        redisCache.bitmaps().setBit(keyBuilder.build(TestKeys.BITMAP), 2, true);
        redisCache.bitmaps().setBit(keyBuilder.build(TestKeys.BITMAP), 2, true);
        assertTrue(redisCache.bitmaps().getBit(keyBuilder.build(TestKeys.BITMAP), 0));
        assertFalse(redisCache.bitmaps().getBit(keyBuilder.build(TestKeys.BITMAP), 1));
        assertEquals(2L, redisCache.bitmaps().bitCount(keyBuilder.build(TestKeys.BITMAP)), "幂等置位不重复计数");
        // 位串 101（今天=最低位）：BITFIELD u3 取 3 位无符号数=5
        assertEquals(5L, redisCache.bitmaps().getUnsigned(keyBuilder.build(TestKeys.BITMAP), 3, 0));
        // 非回文位串锁死位序规范化：bits 0/1 置位（011），原生 BITFIELD MSB 位序会读成 110=6
        redisCache.delete(keyBuilder.build(TestKeys.BITMAP));
        redisCache.bitmaps().setBit(keyBuilder.build(TestKeys.BITMAP), 0, true);
        redisCache.bitmaps().setBit(keyBuilder.build(TestKeys.BITMAP), 1, true);
        assertEquals(3L, redisCache.bitmaps().getUnsigned(keyBuilder.build(TestKeys.BITMAP), 3, 0));
    }

    @Test
    void geoAddSearchAscendingWithDistance() {
        redisCache.delete(keyBuilder.build(TestKeys.GEO));
        // 同一纬度上经度每差 0.01 约 960 米
        // center 在圆心（距离 0），away 在经度 +0.01°（≈960m）
        redisCache.geos().add(keyBuilder.build(TestKeys.GEO), 120.00, 30.00, "center");
        redisCache.geos().add(keyBuilder.build(TestKeys.GEO), 120.01, 30.00, "away");
        List<com.localink.cache.RedisGeoOps.GeoEntry> results = redisCache.geos()
                .search(keyBuilder.build(TestKeys.GEO), 120.00, 30.00, 5000, 10);
        assertEquals(2, results.size());
        assertEquals("center", results.get(0).member(), "按距离升序");
        assertEquals(0.0, results.get(0).distanceMeters(), 1.0, "圆心自身距离为 0");
        assertEquals("away", results.get(1).member());
        assertEquals(960.0, results.get(1).distanceMeters(), 50.0, "经度 0.01°≈960m");
        // 半径过滤：500m 内只有 center
        List<com.localink.cache.RedisGeoOps.GeoEntry> tight = redisCache.geos()
                .search(keyBuilder.build(TestKeys.GEO), 120.00, 30.00, 500, 10);
        assertEquals(List.of("center"), tight.stream().map(com.localink.cache.RedisGeoOps.GeoEntry::member).toList());
    }

    @Test
    void commonHasKeyDeleteExpire() {
        assertFalse(redisCache.hasKey(stringKey));
        redisCache.strings().set(stringKey, "v");
        assertTrue(redisCache.hasKey(stringKey));
        assertTrue(redisCache.expire(stringKey, Duration.ofSeconds(30)));
        Long expire = redisCache.getExpire(stringKey);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 30);
        redisCache.delete(stringKey);
        assertFalse(redisCache.hasKey(stringKey));
    }

    @Test
    void commonDeleteEmptyCollectionNoError() {
        redisCache.delete(List.of());
    }

    private void seedZset() {
        redisCache.zsets().add(zsetKey, "a", 1);
        redisCache.zsets().add(zsetKey, "b", 3);
        redisCache.zsets().add(zsetKey, "c", 2);
    }
}
