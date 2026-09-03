package com.localink.cache.test;

import com.localink.cache.BloomFilterRegistry;
import com.localink.cache.KeyBuilder;
import com.localink.cache.config.CacheProperties;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BloomFilterRegistryIntegrationTest {

    private static final String ALIAS = "demo";
    private static final String FULL_KEY = "lk:test:m28:bloom";

    @Autowired
    private BloomFilterRegistry bloomFilterRegistry;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private KeyBuilder keyBuilder;

    @AfterAll
    void cleanup() {
        redissonClient.getBloomFilter(FULL_KEY).delete();
    }

    @Test
    void registryBeanAutoConfigured() {
        assertNotNull(bloomFilterRegistry);
        assertEquals(FULL_KEY, keyBuilder.build(TestKeys.BLOOM).getKey());
    }

    @Test
    void addedValueContainedAndUnaddedValueNot() {
        String present = String.valueOf(System.nanoTime());
        String absent = String.valueOf(System.nanoTime());

        assertTrue(bloomFilterRegistry.add(ALIAS, present));

        assertTrue(bloomFilterRegistry.contains(ALIAS, present));
        assertFalse(bloomFilterRegistry.contains(ALIAS, absent));
    }

    @Test
    void registryOperatesOnGovernedPrefixedKey() {
        String value = String.valueOf(System.nanoTime());
        bloomFilterRegistry.add(ALIAS, value);

        assertTrue(redissonClient.getBloomFilter(FULL_KEY).contains(value));
    }

    @Test
    void unknownAliasFailsFast() {
        LocalinkException addEx = assertThrows(LocalinkException.class,
                () -> bloomFilterRegistry.add("nope", "1"));
        LocalinkException containsEx = assertThrows(LocalinkException.class,
                () -> bloomFilterRegistry.contains("nope", "1"));

        assertEquals(BaseCode.SYSTEM_ERROR.getCode(), addEx.getCode());
        assertEquals(BaseCode.SYSTEM_ERROR.getCode(), containsEx.getCode());
    }

    @Test
    void reconstructionWithSameParamsReusesExistingFilter() {
        String value = String.valueOf(System.nanoTime());
        bloomFilterRegistry.add(ALIAS, value);

        BloomFilterRegistry reconstructed = new BloomFilterRegistry(redissonClient, keyBuilder, sameDemoProperties());

        assertTrue(reconstructed.contains(ALIAS, value));
    }

    @Test
    void reconstructionWithConflictingParamsFailsFast() {
        CacheProperties properties = sameDemoProperties();
        properties.getBloom().getFilters().get(ALIAS).setExpectedInsertions(5000);

        LocalinkException ex = assertThrows(LocalinkException.class,
                () -> new BloomFilterRegistry(redissonClient, keyBuilder, properties));

        assertTrue(ex.getMessage().contains("参数不一致"));
    }

    private CacheProperties sameDemoProperties() {
        CacheProperties properties = new CacheProperties();
        CacheProperties.Filter filter = new CacheProperties.Filter();
        filter.setKeyTemplate(TestKeys.BLOOM.template());
        filter.setExpectedInsertions(1000);
        filter.setFalseProbability(0.03);
        properties.getBloom().getFilters().put(ALIAS, filter);
        return properties;
    }
}
