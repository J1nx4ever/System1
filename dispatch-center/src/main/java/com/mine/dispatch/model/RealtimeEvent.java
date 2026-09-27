package com.mine.dispatch.model;

public record RealtimeEvent(String type, String simulationId, long serverTimeMs, Object data) {
    public static RealtimeEvent of(String type, String simulationId, Object data) {
        return new RealtimeEvent(type, simulationId, System.currentTimeMillis(), data);
    }
}
