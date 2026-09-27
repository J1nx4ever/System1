package com.mine.dispatch.model;

import java.util.Map;

public record StatusView(
        String simulationId,
        long serverTimeMs,
        boolean online,
        Map<Integer, Boolean> vehicleStale,
        Map<Integer, Boolean> shovelStale) {}
