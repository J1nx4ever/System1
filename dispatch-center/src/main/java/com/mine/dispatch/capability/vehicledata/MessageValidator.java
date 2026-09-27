package com.mine.dispatch.capability.vehicledata;

import mine.field.v1.MapState;
import mine.field.v1.RoadNode;
import mine.field.v1.RoadSegment;
import mine.field.v1.ShovelState;
import mine.field.v1.VehicleState;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class MessageValidator {
    public void validate(ParsedTopic topic, MapState message) {
        common(topic, message.getSimulationId(), message.getSequence(), message.getSimTimeSeconds());
        require(message.getImageWidthPx() > 0 && message.getImageHeightPx() > 0,
                "map image dimensions must be positive");
        require(!message.getImageFile().isBlank(), "map image_file is blank");
        Set<String> nodeIds = new HashSet<>();
        for (RoadNode node : message.getNodesList()) {
            require(!node.getNodeId().isBlank(), "road node id is blank");
            require(nodeIds.add(node.getNodeId()), "duplicate road node id: " + node.getNodeId());
            finite(node.getX(), "road node x");
            finite(node.getY(), "road node y");
        }
        Set<String> segmentIds = new HashSet<>();
        for (RoadSegment segment : message.getSegmentsList()) {
            require(!segment.getSegmentId().isBlank(), "road segment id is blank");
            require(segmentIds.add(segment.getSegmentId()),
                    "duplicate road segment id: " + segment.getSegmentId());
            require(nodeIds.contains(segment.getFromNodeId()),
                    "unknown segment from_node_id: " + segment.getFromNodeId());
            require(nodeIds.contains(segment.getToNodeId()),
                    "unknown segment to_node_id: " + segment.getToNodeId());
            finite(segment.getLengthModelUnits(), "segment length");
            require(segment.getLengthModelUnits() >= 0, "segment length is negative");
        }
    }

    public void validate(ParsedTopic topic, VehicleState message) {
        common(topic, message.getSimulationId(), message.getSequence(), message.getSimTimeSeconds());
        require(topic.entityId() != null && topic.entityId() == message.getVehicleId(),
                "vehicle topic id does not match payload");
        require(message.getSentWallTimeMs() > 0, "vehicle sent_wall_time_ms must be positive");
        finite(message.getX(), "vehicle x");
        finite(message.getY(), "vehicle y");
        finite(message.getSpeedMps(), "vehicle speed_mps");
        finite(message.getPayloadTons(), "vehicle payload_tons");
        require(message.getSpeedMps() >= 0, "vehicle speed_mps is negative");
        require(message.getPayloadTons() >= 0, "vehicle payload_tons is negative");
    }

    public void validate(ParsedTopic topic, ShovelState message) {
        common(topic, message.getSimulationId(), message.getSequence(), message.getSimTimeSeconds());
        require(topic.entityId() != null && topic.entityId() == message.getShovelId(),
                "shovel topic id does not match payload");
        finite(message.getX(), "shovel x");
        finite(message.getY(), "shovel y");
        finite(message.getOreGrade(), "shovel ore_grade");
        finite(message.getRemainingTons(), "shovel remaining_tons");
        require(message.getWaitingTrucks() >= 0, "shovel waiting_trucks is negative");
        require(message.getTrucksInService() >= 0, "shovel trucks_in_service is negative");
        require(message.getRemainingTons() >= 0, "shovel remaining_tons is negative");
    }

    private void common(ParsedTopic topic, String simulationId, long sequence, double simTime) {
        require(!simulationId.isBlank(), "simulation_id is blank");
        require(topic.simulationId().equals(simulationId),
                "topic simulation id does not match payload");
        require(sequence > 0, "sequence must be positive");
        finite(simTime, "sim_time_seconds");
        require(simTime >= 0, "sim_time_seconds is negative");
    }

    private static void finite(double value, String field) {
        require(Double.isFinite(value), field + " is not finite");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
