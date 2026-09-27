package com.mine.dispatch.capability.monitoring;

import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.capability.vehicledata.LatestStateStore;
import com.mine.dispatch.model.*;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StateViewService {
    private final LatestStateStore store;
    private final DispatchProperties properties;

    public StateViewService(LatestStateStore store, DispatchProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public SimulationSnapshot snapshot(String simulationId) {
        return snapshot(store.data(simulationId), System.currentTimeMillis());
    }

    public List<SimulationSummary> summaries() {
        long now = System.currentTimeMillis();
        return store.all().stream().map(data -> new SimulationSummary(
                data.simulationId(),
                isOnline(data.lastReceivedAtMs(), now),
                data.lastReceivedAtMs(),
                data.globalMaxSequence(),
                data.vehicles().size(),
                data.shovels().size())).toList();
    }

    public StatusView status(String simulationId) {
        LatestStateStore.SimulationData data = store.data(simulationId);
        long now = System.currentTimeMillis();
        Map<Integer, Boolean> vehicleStale = new LinkedHashMap<>();
        for (VehicleStateView vehicle : data.vehicles()) {
            vehicleStale.put(vehicle.vehicleId(), stale(vehicle.receivedAtMs(), now,
                    properties.getStaleness().getVehicleMillis()));
        }
        Map<Integer, Boolean> shovelStale = new LinkedHashMap<>();
        for (ShovelStateView shovel : data.shovels()) {
            shovelStale.put(shovel.shovelId(), stale(shovel.receivedAtMs(), now,
                    properties.getStaleness().getShovelMillis()));
        }
        return new StatusView(simulationId, now, isOnline(data.lastReceivedAtMs(), now),
                vehicleStale, shovelStale);
    }

    private SimulationSnapshot snapshot(LatestStateStore.SimulationData data, long now) {
        MapStateView map = data.map() == null ? null : data.map().withStale(
                stale(data.map().receivedAtMs(), now, properties.getStaleness().getSimulationMillis()));
        List<VehicleStateView> vehicles = data.vehicles().stream()
                .map(value -> value.withStale(stale(value.receivedAtMs(), now,
                        properties.getStaleness().getVehicleMillis())))
                .toList();
        List<ShovelStateView> shovels = data.shovels().stream()
                .map(value -> value.withStale(stale(value.receivedAtMs(), now,
                        properties.getStaleness().getShovelMillis())))
                .toList();
        return new SimulationSnapshot(data.simulationId(), now,
                isOnline(data.lastReceivedAtMs(), now), data.lastReceivedAtMs(),
                data.globalMaxSequence(), map, vehicles, shovels);
    }

    private boolean isOnline(long lastReceivedAt, long now) {
        return lastReceivedAt > 0 && !stale(lastReceivedAt, now,
                properties.getStaleness().getSimulationMillis());
    }

    private static boolean stale(long receivedAt, long now, long threshold) {
        return receivedAt <= 0 || now - receivedAt > threshold;
    }
}
