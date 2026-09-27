package com.mine.dispatch.model;

public record ShovelStateView(
        String simulationId,
        long sequence,
        double simTimeSeconds,
        int shovelId,
        String shovelName,
        double x,
        double y,
        boolean available,
        boolean busy,
        int waitingTrucks,
        int trucksInService,
        double oreGrade,
        double remainingTons,
        boolean producesOre,
        long receivedAtMs,
        boolean stale) {
    public ShovelStateView withStale(boolean value) {
        return new ShovelStateView(simulationId, sequence, simTimeSeconds, shovelId,
                shovelName, x, y, available, busy, waitingTrucks, trucksInService,
                oreGrade, remainingTons, producesOre, receivedAtMs, value);
    }
}
