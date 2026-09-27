package com.mine.dispatch.model;

public record ManualDispatchCommand(
        String commandId,
        String simulationId,
        int vehicleId,
        String targetNode,
        String previousDestinationNode,
        long requestedAtMs,
        Long publishedAtMs,
        Long completedAtMs,
        String status,
        String statusMessage) {
}
