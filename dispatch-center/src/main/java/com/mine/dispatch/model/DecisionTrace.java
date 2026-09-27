package com.mine.dispatch.model;

public record DecisionTrace(
        String decisionId,
        String simulationId,
        String decisionType,
        String decisionSource,
        Integer vehicleId,
        Integer shovelId,
        String triggerEvent,
        String inputSummaryJson,
        String modelOutputJson,
        String explanation,
        String validationStatus,
        String validationDetail,
        String commandId,
        String commandTopic,
        long createdAtMs,
        long validatedAtMs,
        Long publishedAtMs,
        Long ackAtMs,
        String ackStatus,
        String ackDetail) {
}
