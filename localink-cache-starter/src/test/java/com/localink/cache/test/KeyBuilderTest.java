package com.localink.cache.test;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.config.CacheProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class KeyBuilderTest {

    private final KeyBuilder keyBuilder = new KeyBuilder(new CacheProperties().getKeyPrefix());

    @Test
    void prefixAppliedToNoArgTemplate() {
        assertEquals("lk:test:m22:string", keyBuilder.build(TestKeys.STRING).getKey());
    }

    @Test
    void multipleArgsFormattedInOrder() {
        assertEquals("lk:test:m22:pair:a:1", keyBuilder.build(TestKeys.PAIR, "a", 1).getKey());
    }

    @Test
    void hashTagBracesPreserved() {
        assertEquals("lk:test:m22:tag:{10}", keyBuilder.build(TestKeys.HASH_TAG, 10).getKey());
    }

    @Test
    void defaultPrefixIsLocalink() {
        assertEquals("lk:", new CacheProperties().getKeyPrefix());
    }

    @Test
    void customPrefixApplied() {
        assertEquals("lk:dev:test:m22:string", new KeyBuilder("lk:dev:").build(TestKeys.STRING).getKey());
    }

    @Test
    void keyBuildEqualsAndHashCode() {
        KeyBuild first = keyBuilder.build(TestKeys.PAIR, "a", 1);
        KeyBuild second = keyBuilder.build(TestKeys.PAIR, "a", 1);
        KeyBuild other = keyBuilder.build(TestKeys.PAIR, "b", 1);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
        assertEquals("lk:test:m22:pair:a:1", first.toString());
    }
}
