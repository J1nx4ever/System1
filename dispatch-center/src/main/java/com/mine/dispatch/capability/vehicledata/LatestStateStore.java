package com.mine.dispatch.capability.vehicledata;

import com.mine.dispatch.model.MapStateView;
import com.mine.dispatch.model.ShovelStateView;
import com.mine.dispatch.model.VehicleStateView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LatestStateStore {
    private final Map<String, SimulationState> simulations = new ConcurrentHashMap<>();

    public UpdateResult apply(MapStateView state) {
        return simulation(state.simulationId()).applyMap(state);
    }

    public UpdateResult apply(VehicleStateView state) {
        return simulation(state.simulationId()).applyVehicle(state);
    }

    public UpdateResult apply(ShovelStateView state) {
        return simulation(state.simulationId()).applyShovel(state);
    }

    public SimulationData data(String simulationId) {
        SimulationState state = simulations.get(simulationId);
        return state == null ? SimulationData.empty(simulationId) : state.copy();
    }

    public List<SimulationData> all() {
        return simulations.values().stream()
                .map(SimulationState::copy)
                .sorted(Comparator.comparing(SimulationData::simulationId))
                .toList();
    }

    public Set<String> simulationIds() {
        return Set.copyOf(simulations.keySet());
    }

    private SimulationState simulation(String id) {
        return simulations.computeIfAbsent(id, SimulationState::new);
    }

    public record UpdateResult(boolean accepted, boolean simulationRestarted) {}

    public record SimulationData(
            String simulationId,
            long lastReceivedAtMs,
            long globalMaxSequence,
            double maxSimTimeSeconds,
            MapStateView map,
            List<VehicleStateView> vehicles,
            List<ShovelStateView> shovels) {
        static SimulationData empty(String id) {
            return new SimulationData(id, 0, 0, 0, null, List.of(), List.of());
        }
    }

    private static final class SimulationState {
        private final String id;
        private final Map<Integer, VehicleStateView> vehicles = new ConcurrentHashMap<>();
        private final Map<Integer, ShovelStateView> shovels = new ConcurrentHashMap<>();
        private MapStateView map;
        private long lastReceivedAtMs;
        private long globalMaxSequence;
        private double maxSimTimeSeconds;
        private long maxVehicleWallTimeMs;

        private SimulationState(String id) {
            this.id = id;
        }

        synchronized UpdateResult applyMap(MapStateView incoming) {
            // MapState is retained and can legitimately carry a much smaller global sequence
            // after an MQTT reconnect. Geometry is a complete snapshot, so replacement is safe.
            map = incoming;
            observe(incoming.sequence(), incoming.simTimeSeconds(), incoming.receivedAtMs());
            return new UpdateResult(true, false);
        }

        synchronized UpdateResult applyVehicle(VehicleStateView incoming) {
            boolean restarted = detectsRestart(incoming);
            if (restarted) {
                vehicles.clear();
                shovels.clear();
                globalMaxSequence = 0;
                maxSimTimeSeconds = 0;
                maxVehicleWallTimeMs = 0;
            }

            VehicleStateView previous = vehicles.get(incoming.vehicleId());
            if (previous != null && incoming.sequence() <= previous.sequence()) {
                return new UpdateResult(false, restarted);
            }
            vehicles.put(incoming.vehicleId(), incoming);
            maxVehicleWallTimeMs = Math.max(maxVehicleWallTimeMs, incoming.sentWallTimeMs());
            observe(incoming.sequence(), incoming.simTimeSeconds(), incoming.receivedAtMs());
            return new UpdateResult(true, restarted);
        }

        synchronized UpdateResult applyShovel(ShovelStateView incoming) {
            ShovelStateView previous = shovels.get(incoming.shovelId());
            if (previous != null && incoming.sequence() <= previous.sequence()) {
                return new UpdateResult(false, false);
            }
            shovels.put(incoming.shovelId(), incoming);
            observe(incoming.sequence(), incoming.simTimeSeconds(), incoming.receivedAtMs());
            return new UpdateResult(true, false);
        }

        private boolean detectsRestart(VehicleStateView incoming) {
            if (globalMaxSequence < 100 || incoming.sequence() >= 100) return false;
            if (incoming.sequence() + 100 >= globalMaxSequence) return false;
            if (incoming.simTimeSeconds() + 2 >= maxSimTimeSeconds) return false;
            return incoming.sentWallTimeMs() >= maxVehicleWallTimeMs;
        }

        private void observe(long sequence, double simTime, long receivedAt) {
            globalMaxSequence = Math.max(globalMaxSequence, sequence);
            maxSimTimeSeconds = Math.max(maxSimTimeSeconds, simTime);
            lastReceivedAtMs = Math.max(lastReceivedAtMs, receivedAt);
        }

        synchronized SimulationData copy() {
            List<VehicleStateView> vehicleCopy = new ArrayList<>(vehicles.values());
            vehicleCopy.sort(Comparator.comparingInt(VehicleStateView::vehicleId));
            List<ShovelStateView> shovelCopy = new ArrayList<>(shovels.values());
            shovelCopy.sort(Comparator.comparingInt(ShovelStateView::shovelId));
            return new SimulationData(id, lastReceivedAtMs, globalMaxSequence,
                    maxSimTimeSeconds, map, List.copyOf(vehicleCopy), List.copyOf(shovelCopy));
        }
    }
}
