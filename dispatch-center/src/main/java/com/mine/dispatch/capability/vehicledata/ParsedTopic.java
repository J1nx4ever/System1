package com.mine.dispatch.capability.vehicledata;

public record ParsedTopic(String simulationId, Type type, Integer entityId) {
    public enum Type { MAP, VEHICLE, SHOVEL }
}
