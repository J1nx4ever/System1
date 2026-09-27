package com.mine.dispatch.model;

public record AuditEntry(String auditId, String requestId, String actorUserId,
                         String actorUsername, String actorRole, String operation,
                         String objectType, String objectId, long occurredAtMs,
                         String result, String detail) {}
