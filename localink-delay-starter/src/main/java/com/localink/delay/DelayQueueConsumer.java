package com.localink.delay;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 延迟队列消费模板基类（贴 AbstractKafkaConsumer 纪律）：子类声明 baseQueue 与业务，
 * 框架为每个分片起独立阻塞消费线程（take 到即离队——无 ack 语义）。
 *
 * <p>可靠性三件套：失败自动重投（退避 1s、最多 3 次、计数在信封）→ 耗尽 error 告警丢弃 →
 * 业务幂等 + 人工兜底。RBlockingQueue 不提供 ack，take 后进程崩溃即丢——教学取舍：
 * 关单类任务丢一条的后果=晚关/不关，由 M5-A 对账与库存回灌兜底，任务卡有声明。</p>
 */
@Slf4j
public abstract class DelayQueueConsumer {

    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    private final RedissonClient redissonClient;
    private final DelayQueuePublisher publisher;
    private final String baseQueue;
    private final int shards;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    protected DelayQueueConsumer(RedissonClient redissonClient, DelayQueuePublisher publisher,
                                 String baseQueue, int shards) {
        this.redissonClient = redissonClient;
        this.publisher = publisher;
        this.baseQueue = baseQueue;
        this.shards = shards;
    }

    /**
     * 业务处理：抛异常触发重投。
     */
    protected abstract void doConsume(String payload);

    /**
     * 失败钩子（重投前调用，默认空实现）：观测/补偿的挂点。
     */
    protected void afterFailure(String payload, int retryCount, Throwable cause) {
    }

    /**
     * 框架启动：每分片一个消费线程。SmartLifecycle 由自动配置驱动。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newFixedThreadPool(shards, thread -> {
            Thread t = new Thread(thread, "delay-consumer-" + baseQueue);
            t.setDaemon(true);
            return t;
        });
        for (int shard = 0; shard < shards; shard++) {
            String queueName = shardQueueName(shard);
            executor.submit(() -> consumeLoop(queueName));
        }
        log.info("延迟消费启动: baseQueue={}, shards={}", baseQueue, shards);
    }

    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void consumeLoop(String queueName) {
        RBlockingQueue<String> queue = redissonClient.getBlockingQueue(queueName,
                org.redisson.client.codec.StringCodec.INSTANCE);
        while (running.get()) {
            try {
                String raw = queue.take();
                DelayMessage message = JSON.parseObject(raw, DelayMessage.class);
                try {
                    doConsume(message.payload());
                } catch (Throwable t) {
                    handleFailure(queueName, message, t);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("延迟消费循环异常, queue={}", queueName, e);
            }
        }
    }

    private void handleFailure(String queueName, DelayMessage message, Throwable cause) {
        afterFailure(message.payload(), message.retryCount(), cause);
        if (message.retryCount() >= MAX_RETRIES) {
            log.error("延迟任务重试耗尽[告警]丢弃: queue={}, payload={}, retries={}",
                    queueName, message.payload(), message.retryCount(), cause);
            return;
        }
        RBlockingQueue<String> destination = redissonClient.getBlockingQueue(queueName,
                org.redisson.client.codec.StringCodec.INSTANCE);
        redissonClient.getDelayedQueue(destination)
                .offer(JSON.toJSONString(message.retry()),
                        RETRY_BACKOFF.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        log.warn("延迟任务失败重投: queue={}, payload={}, retry={}/{}",
                queueName, message.payload(), message.retryCount() + 1, MAX_RETRIES);
    }

    private String shardQueueName(int shard) {
        return publisher.queueName(baseQueue, shardKeyOf(shard));
    }

    /**
     * 与 Publisher 的分片路由保持一致的 shardKey：分片号本身作 key（hash(整数)=整数）。
     */
    private static Integer shardKeyOf(int shard) {
        return shard;
    }
}
