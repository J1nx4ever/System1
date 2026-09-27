package com.mine.dispatch.model;

public record SimulationSummary(
        String simulationId,
        boolean online,
        long lastReceivedAtMs,
        long globalMaxSequence,
        int vehicleCount,
        int shovelCount) {}
