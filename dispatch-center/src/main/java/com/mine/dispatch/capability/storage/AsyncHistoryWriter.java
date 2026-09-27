package com.mine.dispatch.capability.storage;

import com.mine.dispatch.capability.monitoring.IngestionMetrics;
import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.model.MapStateView;
import com.mine.dispatch.model.ShovelStateView;
import com.mine.dispatch.model.VehicleStateView;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.*;

@Service
public class AsyncHistoryWriter {
    private static final Logger log = LoggerFactory.getLogger(AsyncHistoryWriter.class);

    private final DatabaseRepository repository;
    private final IngestionMetrics metrics;
    private final ThreadPoolExecutor executor;

    public AsyncHistoryWriter(DatabaseRepository repository,
                              IngestionMetrics metrics,
                              DispatchProperties properties) {
        this.repository = repository;
        this.metrics = metrics;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getStorage().getWriterQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "state-history-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, ignored) -> metrics.persistenceDropped());
    }

    public void persist(MapStateView state) { submit(() -> repository.persist(state)); }
    public void persist(VehicleStateView state) { submit(() -> repository.persist(state)); }
    public void persist(ShovelStateView state) { submit(() -> repository.persist(state)); }
    public void rejection(String topic, String reason, int payloadSize) {
        submit(() -> repository.rejection(topic, reason, payloadSize));
    }

    public boolean flush(Duration timeout) {
        try {
            executor.submit(() -> {}).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    private void submit(CheckedRunnable runnable) {
        executor.execute(() -> {
            try {
                runnable.run();
            } catch (Exception exception) {
                log.error("State persistence failed", exception);
            }
        });
    }

    @PreDestroy
    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }
}
