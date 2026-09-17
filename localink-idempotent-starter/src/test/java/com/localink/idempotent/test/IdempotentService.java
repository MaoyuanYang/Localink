package com.localink.idempotent.test;

import com.localink.idempotent.DuplicatePolicy;
import com.localink.idempotent.RepeatExecuteLimit;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class IdempotentService {

    private final AtomicInteger returningCount = new AtomicInteger();
    private final AtomicInteger voidCount = new AtomicInteger();
    private final AtomicInteger failCount = new AtomicInteger();
    private final AtomicInteger noMarkerCount = new AtomicInteger();

    @RepeatExecuteLimit(name = "t-returning", key = "#k")
    public String returning(String k) {
        returningCount.incrementAndGet();
        return "v-" + k;
    }

    @RepeatExecuteLimit(name = "t-void", key = "#k")
    public void voidMethod(String k) {
        voidCount.incrementAndGet();
    }

    @RepeatExecuteLimit(name = "t-fail", key = "#k")
    public String failFirst(String k) {
        if (failCount.incrementAndGet() == 1) {
            throw new IllegalStateException("boom");
        }
        return "ok-" + k;
    }

    @RepeatExecuteLimit(name = "t-reject", key = "#k", onDuplicate = DuplicatePolicy.REJECT)
    public String rejectMode(String k) {
        return "r-" + k;
    }

    @RepeatExecuteLimit(name = "t-nomarker", key = "#k", markerTtl = 0)
    public String noMarker(String k) {
        noMarkerCount.incrementAndGet();
        return "n-" + k;
    }

    public int returningCount() {
        return returningCount.get();
    }

    public int voidCount() {
        return voidCount.get();
    }

    public int failCount() {
        return failCount.get();
    }

    public int noMarkerCount() {
        return noMarkerCount.get();
    }
}
