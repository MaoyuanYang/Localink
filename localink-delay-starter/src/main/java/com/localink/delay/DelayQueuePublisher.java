package com.localink.delay;

import com.localink.delay.config.DelayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 延迟任务投递门面：RDelayedQueue 承接到期搬运（ZSet 扛时间 + 后台线程移入目标 BlockingQueue），
 * 队列按 key 分片（hash(shardKey) % shards）——分片的意义是消费侧每片一个阻塞线程，吞吐水平扩展。
 * RDelayedQueue 实例按目标队列名缓存（重复创建同名实例是昂贵操作）。
 */
@Slf4j
@RequiredArgsConstructor
public class DelayQueuePublisher {

    private final RedissonClient redissonClient;
    private final DelayProperties properties;
    private final Map<String, RDelayedQueue> delayedQueues = new ConcurrentHashMap<>();

    /**
     * 分片投递：payload 到期后进入对应分片目标队列，由订阅该分片的消费线程取走。
     */
    public void offerSharded(String baseQueue, Object shardKey, String payload, Duration delay) {
        String queueName = queueName(baseQueue, shardKey);
        DelayMessage message = DelayMessage.of(payload);
        delayedQueues.computeIfAbsent(queueName, name -> {
            RBlockingQueue<String> destination = redissonClient.getBlockingQueue(name,
                    org.redisson.client.codec.StringCodec.INSTANCE);
            return redissonClient.getDelayedQueue(destination);
        }).offer(com.alibaba.fastjson2.JSON.toJSONString(message),
                delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        log.debug("延迟任务已投递: queue={}, delay={}ms, payload={}", queueName, delay.toMillis(), payload);
    }

    public String queueName(String baseQueue, Object shardKey) {
        int shard = Math.floorMod(shardKey.hashCode(), properties.getShards());
        return properties.getKeyPrefix() + "delay:" + baseQueue + ":" + shard;
    }
}
