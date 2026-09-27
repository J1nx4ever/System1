package com.mine.dispatch.capability.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.model.DecisionTrace;
import com.mine.dispatch.model.RealtimeEvent;
import com.mine.dispatch.web.RealtimeHub;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
public class DecisionTraceService {
    private final DecisionTraceRepository repository;
    private final ObjectMapper json;
    private final RealtimeHub realtime;

    public DecisionTraceService(DecisionTraceRepository repository, ObjectMapper json, RealtimeHub realtime) {
        this.repository = repository;
        this.json = json;
        this.realtime = realtime;
    }

    public DecisionTrace create(String simulationId, String type, String source,
                                Integer vehicleId, Integer shovelId, String triggerEvent,
                                Object inputSummary, Object modelOutput, String explanation,
                                String validationDetail, String commandId, String commandTopic) {
        long now = System.currentTimeMillis();
        try {
            DecisionTrace trace = new DecisionTrace(
                    UUID.randomUUID().toString(), simulationId, type, source, vehicleId, shovelId,
                    triggerEvent, json.writeValueAsString(inputSummary), json.writeValueAsString(modelOutput),
                    blankToNull(explanation), "PASSED", validationDetail, commandId, commandTopic,
                    now, now, null, null, "CREATED", "待发布");
            repository.insert(trace);
            broadcast(trace);
            return trace;
        } catch (SQLException | JsonProcessingException exception) {
            throw new IllegalStateException("decision trace persistence failed", exception);
        }
    }

    public DecisionTrace published(String commandId) {
        try {
            DecisionTrace trace = repository.markPublished(commandId, System.currentTimeMillis());
            broadcast(trace);
            return trace;
        } catch (SQLException exception) {
            throw new IllegalStateException("decision trace publish update failed", exception);
        }
    }

    public DecisionTrace publishFailed(String commandId, String detail) {
        try {
            DecisionTrace trace = repository.markPublishFailed(commandId, detail, System.currentTimeMillis());
            broadcast(trace);
            return trace;
        } catch (SQLException exception) {
            throw new IllegalStateException("decision trace failure update failed", exception);
        }
    }

    public void acknowledge(String commandId, String status, String detail, long atMs) {
        try {
            repository.acknowledge(commandId, status, detail, atMs).ifPresent(this::broadcast);
        } catch (SQLException exception) {
            throw new IllegalStateException("decision trace acknowledgement failed", exception);
        }
    }

    public List<DecisionTrace> list(String simulationId, Integer vehicleId, Integer shovelId,
                                    String type, int limit) {
        try {
            return repository.list(simulationId, vehicleId, shovelId, type,
                    Math.max(1, Math.min(500, limit)));
        } catch (SQLException exception) {
            throw new IllegalStateException("decision trace query failed", exception);
        }
    }

    private void broadcast(DecisionTrace trace) {
        realtime.broadcast(RealtimeEvent.of("decisionTrace", trace.simulationId(), trace));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
