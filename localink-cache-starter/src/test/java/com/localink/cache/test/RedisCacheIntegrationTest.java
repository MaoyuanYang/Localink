package com.localink.cache.test;

import com.localink.cache.RedisCache;
import com.localink.cache.model.ZSetEntry;
import org.junit.jupiter.api.AfterEach;
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

    private static final String STRING_KEY = "lk:test:m21:string";
    private static final String STRING_TTL_KEY = "lk:test:m21:string:ttl";
    private static final String HASH_KEY = "lk:test:m21:hash";
    private static final String SET_KEY_A = "lk:test:m21:set:a";
    private static final String SET_KEY_B = "lk:test:m21:set:b";
    private static final String ZSET_KEY = "lk:test:m21:zset";

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanup() {
        redisCache.delete(List.of(STRING_KEY, STRING_TTL_KEY, HASH_KEY, SET_KEY_A, SET_KEY_B, ZSET_KEY));
    }

    @Test
    void redisCacheBeanAutoConfigured() {
        assertNotNull(redisCache);
    }

    @Test
    void stringSetAndGetTypedObject() {
        CacheTestPayload payload = new CacheTestPayload("tom", 3);
        redisCache.strings().set(STRING_KEY, payload);
        CacheTestPayload cached = redisCache.strings().get(STRING_KEY, CacheTestPayload.class);
        assertEquals(payload, cached);
        String raw = stringRedisTemplate.opsForValue().get(STRING_KEY);
        assertNotNull(raw);
        assertTrue(raw.startsWith("{"));
    }

    @Test
    void stringSetPlainValueRawRead() {
        redisCache.strings().set(STRING_KEY, "hello");
        assertEquals("hello", redisCache.strings().getString(STRING_KEY));
        assertEquals("hello", redisCache.strings().get(STRING_KEY, String.class));
    }

    @Test
    void stringSetWithTtl() {
        redisCache.strings().set(STRING_TTL_KEY, "v", Duration.ofSeconds(60));
        Long expire = redisCache.getExpire(STRING_TTL_KEY);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 60);
    }

    @Test
    void stringSetIfAbsent() {
        assertTrue(redisCache.strings().setIfAbsent(STRING_KEY, "first", Duration.ofSeconds(60)));
        assertFalse(redisCache.strings().setIfAbsent(STRING_KEY, "second", Duration.ofSeconds(60)));
        assertEquals("first", redisCache.strings().getString(STRING_KEY));
    }

    @Test
    void stringGetList() {
        List<CacheTestPayload> list = List.of(new CacheTestPayload("a", 1), new CacheTestPayload("b", 2));
        redisCache.strings().set(STRING_KEY, list);
        List<CacheTestPayload> cached = redisCache.strings().getList(STRING_KEY, CacheTestPayload.class);
        assertEquals(list, cached);
    }

    @Test
    void stringGetAndDelete() {
        redisCache.strings().set(STRING_KEY, "once");
        assertEquals("once", redisCache.strings().getAndDelete(STRING_KEY));
        assertFalse(redisCache.hasKey(STRING_KEY));
        assertNull(redisCache.strings().getAndDelete(STRING_KEY));
    }

    @Test
    void stringIncrement() {
        assertEquals(2L, redisCache.strings().increment(STRING_KEY, 2));
        assertEquals(5L, redisCache.strings().increment(STRING_KEY, 3));
    }

    @Test
    void stringGetMissingKeyReturnsNull() {
        assertNull(redisCache.strings().get(STRING_KEY, CacheTestPayload.class));
        assertNull(redisCache.strings().getString(STRING_KEY));
        assertNull(redisCache.strings().getList(STRING_KEY, CacheTestPayload.class));
    }

    @Test
    void hashPutAndGetTyped() {
        CacheTestPayload payload = new CacheTestPayload("jerry", 5);
        redisCache.hashes().put(HASH_KEY, "user", payload);
        CacheTestPayload cached = redisCache.hashes().get(HASH_KEY, "user", CacheTestPayload.class);
        assertEquals(payload, cached);
    }

    @Test
    void hashPutAllAndEntries() {
        Map<String, String> fields = Map.of("id", "1", "name", "tom");
        redisCache.hashes().putAll(HASH_KEY, fields);
        assertEquals(fields, redisCache.hashes().entries(HASH_KEY));
    }

    @Test
    void hashPutAllWithTtl() {
        redisCache.hashes().putAll(HASH_KEY, Map.of("id", "1"), Duration.ofSeconds(60));
        Long expire = redisCache.getExpire(HASH_KEY);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 60);
    }

    @Test
    void hashHasFieldAndDeleteField() {
        redisCache.hashes().put(HASH_KEY, "f1", "v1");
        assertTrue(redisCache.hashes().hasField(HASH_KEY, "f1"));
        assertEquals(1L, redisCache.hashes().delete(HASH_KEY, "f1"));
        assertFalse(redisCache.hashes().hasField(HASH_KEY, "f1"));
    }

    @Test
    void hashIncrementField() {
        assertEquals(3L, redisCache.hashes().increment(HASH_KEY, "counter", 3));
        assertEquals(5L, redisCache.hashes().increment(HASH_KEY, "counter", 2));
    }

    @Test
    void setAddIsMemberSize() {
        assertEquals(3L, redisCache.sets().add(SET_KEY_A, 1L, 2L, 3L));
        assertTrue(redisCache.sets().isMember(SET_KEY_A, 2L));
        assertFalse(redisCache.sets().isMember(SET_KEY_A, 9L));
        assertEquals(3L, redisCache.sets().size(SET_KEY_A));
        assertEquals(1L, redisCache.sets().add(SET_KEY_A, 3L, 4L));
    }

    @Test
    void setRemoveAndMembers() {
        redisCache.sets().add(SET_KEY_A, 1L, 2L, 3L);
        assertEquals(1L, redisCache.sets().remove(SET_KEY_A, 1L));
        Set<Long> members = redisCache.sets().members(SET_KEY_A, Long.class);
        assertEquals(Set.of(2L, 3L), members);
    }

    @Test
    void setIntersect() {
        redisCache.sets().add(SET_KEY_A, 1L, 2L, 3L);
        redisCache.sets().add(SET_KEY_B, 2L, 3L, 4L);
        Set<Long> intersect = redisCache.sets().intersect(SET_KEY_A, SET_KEY_B, Long.class);
        assertEquals(Set.of(2L, 3L), intersect);
    }

    @Test
    void zsetAddScoreSize() {
        assertTrue(redisCache.zsets().add(ZSET_KEY, "a", 1));
        assertTrue(redisCache.zsets().add(ZSET_KEY, "b", 3));
        assertTrue(redisCache.zsets().add(ZSET_KEY, "c", 2));
        assertEquals(3L, redisCache.zsets().size(ZSET_KEY));
        assertEquals(1.0, redisCache.zsets().score(ZSET_KEY, "a"));
        assertFalse(redisCache.zsets().add(ZSET_KEY, "a", 10));
        assertEquals(10.0, redisCache.zsets().score(ZSET_KEY, "a"));
    }

    @Test
    void zsetAddWithTtl() {
        redisCache.zsets().add(ZSET_KEY, "x", 1, Duration.ofSeconds(60));
        Long expire = redisCache.getExpire(ZSET_KEY);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 60);
    }

    @Test
    void zsetIncrementScore() {
        redisCache.zsets().add(ZSET_KEY, "a", 1);
        assertEquals(6.0, redisCache.zsets().incrementScore(ZSET_KEY, "a", 5));
    }

    @Test
    void zsetRankAndReverseRank() {
        seedZset();
        assertEquals(0L, redisCache.zsets().rank(ZSET_KEY, "a"));
        assertEquals(0L, redisCache.zsets().reverseRank(ZSET_KEY, "b"));
        assertNull(redisCache.zsets().rank(ZSET_KEY, "missing"));
    }

    @Test
    void zsetRangeOrdering() {
        seedZset();
        assertEquals(List.of("a", "c", "b"), new ArrayList<>(redisCache.zsets().range(ZSET_KEY, 0, -1, String.class)));
        assertEquals(List.of("b", "c", "a"), new ArrayList<>(redisCache.zsets().reverseRange(ZSET_KEY, 0, -1, String.class)));
    }

    @Test
    void zsetReverseRangeWithScore() {
        seedZset();
        Set<ZSetEntry<String>> entries = redisCache.zsets().reverseRangeWithScore(ZSET_KEY, 0, -1, String.class);
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
        assertEquals(List.of("b"), new ArrayList<>(redisCache.zsets().reverseRangeByScore(ZSET_KEY, 1.5, 10, 0, 1, String.class)));
        assertEquals(List.of("c"), new ArrayList<>(redisCache.zsets().reverseRangeByScore(ZSET_KEY, 1.5, 10, 1, 1, String.class)));
    }

    @Test
    void zsetRemove() {
        seedZset();
        assertEquals(1L, redisCache.zsets().remove(ZSET_KEY, "a"));
        assertEquals(2L, redisCache.zsets().size(ZSET_KEY));
    }

    @Test
    void commonHasKeyDeleteExpire() {
        assertFalse(redisCache.hasKey(STRING_KEY));
        redisCache.strings().set(STRING_KEY, "v");
        assertTrue(redisCache.hasKey(STRING_KEY));
        assertTrue(redisCache.expire(STRING_KEY, Duration.ofSeconds(30)));
        Long expire = redisCache.getExpire(STRING_KEY);
        assertNotNull(expire);
        assertTrue(expire > 0 && expire <= 30);
        redisCache.delete(STRING_KEY);
        assertFalse(redisCache.hasKey(STRING_KEY));
    }

    @Test
    void commonDeleteEmptyCollectionNoError() {
        redisCache.delete(List.of());
    }

    private void seedZset() {
        redisCache.zsets().add(ZSET_KEY, "a", 1);
        redisCache.zsets().add(ZSET_KEY, "b", 3);
        redisCache.zsets().add(ZSET_KEY, "c", 2);
    }
}
