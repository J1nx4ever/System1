package com.mine.dispatch.capability.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.model.DecisionTrace;
import com.mine.dispatch.model.RealtimeEvent;
import com.mine.dispatch.web.RealtimeHub;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DecisionTraceService {
    private static final long SPEED_HEARTBEAT_MS = 10_000;
    private static final long SPEED_CHANGE_GAP_MS = 2_000;
    private static final double SPEED_ACCELERATION_CHANGE_MPS2 = 0.5;
    private static final long SPEED_PENDING_TTL_MS = 60_000;
    private static final int SPEED_PENDING_LIMIT = 10_000;
    private final DecisionTraceRepository repository;
    private final ObjectMapper json;
    private final RealtimeHub realtime;
    private final Map<String, SpeedSample> speedSamples = new HashMap<>();
    private final Map<String, SpeedCandidate> pendingSpeed = new HashMap<>();
    private final ArrayDeque<SpeedCandidate> pendingOrder = new ArrayDeque<>();

    private record SpeedSample(long atMs, double acceleration, int risk) {}
    private record SpeedCandidate(long atMs, String simulationId, int vehicleId,
                                  Object input, Object output, String commandId, String commandTopic) {}

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

    /** Keep every control command, but persist a representative evidence trace per vehicle. */
    public synchronized boolean recordSpeed(String simulationId, int vehicleId, boolean hasFront,
                                            boolean hasConflict, double acceleration, Object input,
                                            Object output, String commandId, String commandTopic) {
        long now = System.currentTimeMillis();
        prunePendingSpeed(now);
        String key = simulationId + ":" + vehicleId;
        int risk = (hasFront ? 1 : 0) | (hasConflict ? 2 : 0);
        SpeedSample previous = speedSamples.get(key);
        boolean sample = previous == null || now - previous.atMs >= SPEED_HEARTBEAT_MS
                || (risk & ~previous.risk) != 0
                || (now - previous.atMs >= SPEED_CHANGE_GAP_MS
                    && (risk != previous.risk
                        || Math.abs(acceleration - previous.acceleration) >= SPEED_ACCELERATION_CHANGE_MPS2));
        if (sample) {
            create(simulationId, "SPEED", "SPEED_INFERENCE", vehicleId, null,
                    "CONTROL_CYCLE_500MS", input, output, null,
                    "agent与观测匹配；单一有限加速度；满足配置安全边界；自适应证据采样",
                    commandId, commandTopic);
            speedSamples.put(key, new SpeedSample(now, acceleration, risk));
        } else {
            SpeedCandidate candidate = new SpeedCandidate(now, simulationId, vehicleId,
                    input, output, commandId, commandTopic);
            pendingSpeed.put(commandId, candidate);
            pendingOrder.addLast(candidate);
            prunePendingSpeed(now);
        }
        return sample;
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

    public synchronized DecisionTrace publishFailed(String commandId, String detail) {
        promotePendingSpeed(commandId);
        try {
            DecisionTrace trace = repository.markPublishFailed(commandId, detail, System.currentTimeMillis());
            broadcast(trace);
            return trace;
        } catch (SQLException exception) {
            throw new IllegalStateException("decision trace failure update failed", exception);
        }
    }

    public synchronized void acknowledge(String commandId, String status, String detail, long atMs) {
        if ("EXECUTED".equals(status)) pendingSpeed.remove(commandId);
        else promotePendingSpeed(commandId);
        try {
            repository.acknowledge(commandId, status, detail, atMs).ifPresent(this::broadcast);
        } catch (SQLException exception) {
            throw new IllegalStateException("decision trace acknowledgement failed", exception);
        }
    }

    private void promotePendingSpeed(String commandId) {
        SpeedCandidate candidate = pendingSpeed.remove(commandId);
        if (candidate == null) return;
        create(candidate.simulationId, "SPEED", "SPEED_INFERENCE", candidate.vehicleId, null,
                "CONTROL_CYCLE_500MS", candidate.input, candidate.output, null,
                "agent与观测匹配；单一有限加速度；满足配置安全边界；异常回执补录",
                candidate.commandId, candidate.commandTopic);
    }

    private void prunePendingSpeed(long now) {
        while (!pendingOrder.isEmpty()) {
            SpeedCandidate oldest = pendingOrder.peekFirst();
            if (pendingSpeed.size() <= SPEED_PENDING_LIMIT
                    && now - oldest.atMs <= SPEED_PENDING_TTL_MS) break;
            pendingOrder.removeFirst();
            pendingSpeed.remove(oldest.commandId, oldest);
        }
        while (!pendingOrder.isEmpty() && !pendingSpeed.containsKey(pendingOrder.peekFirst().commandId)) {
            pendingOrder.removeFirst();
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
