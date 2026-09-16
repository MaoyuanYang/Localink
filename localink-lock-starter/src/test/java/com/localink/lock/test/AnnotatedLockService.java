package com.localink.lock.test;

import com.localink.lock.annotation.ServiceLock;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AnnotatedLockService {

    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();
    private final AtomicInteger entered = new AtomicInteger();

    @ServiceLock(name = "test-order", key = "#orderId")
    public String process(String orderId) {
        return "ok:" + orderId;
    }

    @ServiceLock(name = "test-gate", key = "#token")
    public void gate(String token) throws InterruptedException {
        maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
        Thread.sleep(50);
        entered.incrementAndGet();
        inside.decrementAndGet();
    }

    @ServiceLock(name = "test-slow", key = "#token", waitTime = 200, timeUnit = TimeUnit.MILLISECONDS)
    public void slowHold(String token, CountDownLatch enteredLatch, CountDownLatch release)
            throws InterruptedException {
        enteredLatch.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("slowHold 等待释放超时");
        }
    }

    public int maxConcurrent() {
        return maxInside.get();
    }

    public int totalEntered() {
        return entered.get();
    }
}
