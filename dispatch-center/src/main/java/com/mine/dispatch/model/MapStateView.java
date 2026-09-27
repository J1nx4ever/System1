package com.mine.dispatch.model;

import java.util.List;

public record MapStateView(
        String simulationId,
        long sequence,
        double simTimeSeconds,
        String imageFile,
        int imageWidthPx,
        int imageHeightPx,
        List<RoadNodeView> nodes,
        List<RoadSegmentView> segments,
        long receivedAtMs,
        boolean stale) {
    public MapStateView withStale(boolean value) {
        return new MapStateView(simulationId, sequence, simTimeSeconds, imageFile,
                imageWidthPx, imageHeightPx, nodes, segments, receivedAtMs, value);
    }
}
