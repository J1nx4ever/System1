package com.mine.dispatch.model;

public record VehicleStateView(
        String simulationId,
        long sequence,
        double simTimeSeconds,
        long sentWallTimeMs,
        int vehicleId,
        double x,
        double y,
        double speedMps,
        boolean loaded,
        double payloadTons,
        String currentNode,
        String destinationNode,
        String currentSegmentId,
        int targetLoadSiteId,
        int targetDumpSiteId,
        String statechartState,
        long receivedAtMs,
        boolean stale) {
    public VehicleStateView withStale(boolean value) {
        return new VehicleStateView(simulationId, sequence, simTimeSeconds, sentWallTimeMs,
                vehicleId, x, y, speedMps, loaded, payloadTons, currentNode, destinationNode,
                currentSegmentId, targetLoadSiteId, targetDumpSiteId, statechartState,
                receivedAtMs, value);
    }
}
