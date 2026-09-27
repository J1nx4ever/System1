package com.mine.dispatch.model;

import java.util.List;

public record SimulationSnapshot(
        String simulationId,
        long serverTimeMs,
        boolean online,
        long lastReceivedAtMs,
        long globalMaxSequence,
        MapStateView map,
        List<VehicleStateView> vehicles,
        List<ShovelStateView> shovels) {}
