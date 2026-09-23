package com.localink.delay;

/**
 * 延迟任务信封：payload 为业务载荷（调用方自定义，建议 JSON String）；
 * retryCount 为失败重投计数（框架维护，耗尽告警丢弃）；firstOfferTs 供消费端判超龄。
 */
public record DelayMessage(String payload, int retryCount, long firstOfferTs) {

    public static DelayMessage of(String payload) {
        return new DelayMessage(payload, 0, System.currentTimeMillis());
    }

    DelayMessage retry() {
        return new DelayMessage(payload, retryCount + 1, firstOfferTs);
    }
}
