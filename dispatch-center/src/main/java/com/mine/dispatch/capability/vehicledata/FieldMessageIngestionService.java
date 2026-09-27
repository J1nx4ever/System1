package com.mine.dispatch.capability.vehicledata;

import com.google.protobuf.InvalidProtocolBufferException;
import com.mine.dispatch.capability.monitoring.IngestionMetrics;
import com.mine.dispatch.capability.storage.AsyncHistoryWriter;
import com.mine.dispatch.model.*;
import com.mine.dispatch.web.RealtimeHub;
import mine.field.v1.MapState;
import mine.field.v1.ShovelState;
import mine.field.v1.VehicleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FieldMessageIngestionService {
    private static final Logger log = LoggerFactory.getLogger(FieldMessageIngestionService.class);

    private final TopicParser topicParser;
    private final MessageValidator validator;
    private final LatestStateStore store;
    private final AsyncHistoryWriter history;
    private final RealtimeHub realtimeHub;
    private final IngestionMetrics metrics;

    public FieldMessageIngestionService(TopicParser topicParser,
                                        MessageValidator validator,
                                        LatestStateStore store,
                                        AsyncHistoryWriter history,
                                        RealtimeHub realtimeHub,
                                        IngestionMetrics metrics) {
        this.topicParser = topicParser;
        this.validator = validator;
        this.store = store;
        this.history = history;
        this.realtimeHub = realtimeHub;
        this.metrics = metrics;
    }

    public void handle(String topicName, byte[] payload, int qos, boolean retained) {
        metrics.received();
        try {
            ParsedTopic topic = topicParser.parse(topicName);
            switch (topic.type()) {
                case MAP -> ingestMap(topic, payload, qos, retained);
                case VEHICLE -> ingestVehicle(topic, payload, qos);
                case SHOVEL -> ingestShovel(topic, payload, qos);
            }
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            metrics.rejected();
            history.rejection(topicName, exception.getMessage(), payload.length);
            log.warn("Rejected field message topic={} size={} reason={}",
                    topicName, payload.length, exception.getMessage());
        } catch (RuntimeException exception) {
            metrics.rejected();
            history.rejection(topicName, exception.getClass().getSimpleName(), payload.length);
            log.error("Failed to ingest field message topic={}", topicName, exception);
        }
    }

    private void ingestMap(ParsedTopic topic, byte[] payload, int qos, boolean retained)
            throws InvalidProtocolBufferException {
        MapState message = MapState.parseFrom(payload);
        validator.validate(topic, message);
        if (qos != 1 || !retained) {
            log.warn("MapState arrived with qos={} retained={}; publisher baseline is qos=1 retained=true",
                    qos, retained);
        }
        long now = System.currentTimeMillis();
        MapStateView view = new MapStateView(
                message.getSimulationId(), message.getSequence(), message.getSimTimeSeconds(),
                message.getImageFile(), message.getImageWidthPx(), message.getImageHeightPx(),
                message.getNodesList().stream()
                        .map(node -> new RoadNodeView(node.getNodeId(), node.getX(), node.getY()))
                        .toList(),
                message.getSegmentsList().stream()
                        .map(segment -> new RoadSegmentView(segment.getSegmentId(),
                                segment.getFromNodeId(), segment.getToNodeId(),
                                segment.getLengthModelUnits(), segment.getClosed()))
                        .toList(),
                now, false);
        accept(store.apply(view), "map", view.simulationId(), view, () -> history.persist(view));
    }

    private void ingestVehicle(ParsedTopic topic, byte[] payload, int qos)
            throws InvalidProtocolBufferException {
        VehicleState message = VehicleState.parseFrom(payload);
        validator.validate(topic, message);
        if (qos != 0) {
            log.debug("VehicleState qos={} differs from current AnyLogic baseline qos=0", qos);
        }
        VehicleStateView view = new VehicleStateView(
                message.getSimulationId(), message.getSequence(), message.getSimTimeSeconds(),
                message.getSentWallTimeMs(), message.getVehicleId(), message.getX(), message.getY(),
                message.getSpeedMps(), message.getLoaded(), message.getPayloadTons(),
                message.getCurrentNode(), message.getDestinationNode(), message.getCurrentSegmentId(),
                message.getTargetLoadSiteId(), message.getTargetDumpSiteId(),
                message.getStatechartState(), System.currentTimeMillis(), false);
        accept(store.apply(view), "vehicle", view.simulationId(), view, () -> history.persist(view));
    }

    private void ingestShovel(ParsedTopic topic, byte[] payload, int qos)
            throws InvalidProtocolBufferException {
        ShovelState message = ShovelState.parseFrom(payload);
        validator.validate(topic, message);
        if (qos != 0) {
            log.debug("ShovelState qos={} differs from current AnyLogic baseline qos=0", qos);
        }
        ShovelStateView view = new ShovelStateView(
                message.getSimulationId(), message.getSequence(), message.getSimTimeSeconds(),
                message.getShovelId(), message.getShovelName(), message.getX(), message.getY(),
                message.getAvailable(), message.getBusy(), message.getWaitingTrucks(),
                message.getTrucksInService(), message.getOreGrade(), message.getRemainingTons(),
                message.getProducesOre(), System.currentTimeMillis(), false);
        accept(store.apply(view), "shovel", view.simulationId(), view, () -> history.persist(view));
    }

    private void accept(LatestStateStore.UpdateResult result,
                        String type,
                        String simulationId,
                        Object view,
                        Runnable persistence) {
        if (!result.accepted()) {
            metrics.staleOrDuplicate();
            return;
        }
        metrics.accepted();
        persistence.run();
        if (result.simulationRestarted()) {
            realtimeHub.broadcast(RealtimeEvent.of("simulationRestart", simulationId, null));
        }
        realtimeHub.broadcast(RealtimeEvent.of(type, simulationId, view));
    }
}
