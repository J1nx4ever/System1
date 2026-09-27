package com.mine.dispatch.model;

public record RoadSegmentView(
        String segmentId,
        String fromNodeId,
        String toNodeId,
        double lengthModelUnits,
        boolean closed) {}
