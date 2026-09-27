package com.mine.dispatch.model;

public record TerminalAction(String actionId, String simulationId, String actorUserId,
                             String actorUsername, String actorRole, String objectType,
                             int objectId, String actionType, String commandId, String detail,
                             long createdAtMs, String result, String resultDetail) {}
