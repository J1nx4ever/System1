package com.mine.dispatch.capability.monitoring;

import com.mine.dispatch.capability.vehicledata.LatestStateStore;
import com.mine.dispatch.model.RealtimeEvent;
import com.mine.dispatch.model.StatusView;
import com.mine.dispatch.web.RealtimeHub;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StalenessMonitor {
    private final LatestStateStore store;
    private final StateViewService views;
    private final RealtimeHub hub;
    private final Map<String, StatusFingerprint> previous = new ConcurrentHashMap<>();

    public StalenessMonitor(LatestStateStore store, StateViewService views, RealtimeHub hub) {
        this.store = store;
        this.views = views;
        this.hub = hub;
    }

    @Scheduled(fixedRate = 1000)
    public void evaluate() {
        for (String simulationId : store.simulationIds()) {
            StatusView status = views.status(simulationId);
            StatusFingerprint fingerprint = new StatusFingerprint(
                    status.online(), status.vehicleStale(), status.shovelStale());
            if (!fingerprint.equals(previous.put(simulationId, fingerprint))) {
                hub.broadcast(RealtimeEvent.of("status", simulationId, status));
            }
        }
    }

    private record StatusFingerprint(
            boolean online,
            Map<Integer, Boolean> vehicleStale,
            Map<Integer, Boolean> shovelStale) {}
}
