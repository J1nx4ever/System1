package com.mine.dispatch.model;

public record OperationalAlert(String alertId, String simulationId, String sourceActionId,
                               String objectType, int objectId, String severity, String code,
                               String message, String status, long createdAtMs,
                               String acknowledgedBy, Long acknowledgedAtMs,
                               String processingBy, Long processingAtMs,
                               String closedBy, Long closedAtMs, String resolution, int version) {}
