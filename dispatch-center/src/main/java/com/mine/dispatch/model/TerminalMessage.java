package com.mine.dispatch.model;

public record TerminalMessage(String messageId, String simulationId, String objectType,
                              int objectId, String direction, String senderRole,
                              String senderName, String body, long createdAtMs,
                              String status, String replyToMessageId, String statusDetail) {}
