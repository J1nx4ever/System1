package com.mine.dispatch.capability.monitoring;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IngestionMetrics {
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong staleOrDuplicate = new AtomicLong();
    private final AtomicLong persistenceDropped = new AtomicLong();

    public void received() { received.incrementAndGet(); }
    public void accepted() { accepted.incrementAndGet(); }
    public void rejected() { rejected.incrementAndGet(); }
    public void staleOrDuplicate() { staleOrDuplicate.incrementAndGet(); }
    public void persistenceDropped() { persistenceDropped.incrementAndGet(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("received", received.get());
        result.put("accepted", accepted.get());
        result.put("rejected", rejected.get());
        result.put("staleOrDuplicate", staleOrDuplicate.get());
        result.put("persistenceDropped", persistenceDropped.get());
        return result;
    }
}
