package com.mine.dispatch.capability.manualdispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.model.ManualDispatchCommand;
import com.mine.dispatch.capability.decision.DecisionTraceService;
import com.mine.dispatch.model.RealtimeEvent;
import com.mine.dispatch.web.RealtimeHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ManualDispatchResultService {
    private static final Logger log = LoggerFactory.getLogger(ManualDispatchResultService.class);
    private static final Pattern TOPIC = Pattern.compile(
            "^mine/sim/([A-Za-z0-9_-]{1,64})/result/manual-dispatch/([0-9a-fA-F-]{36})$");
    private static final Set<String> TERMINAL = Set.of("EXECUTED", "REJECTED");

    private final ManualDispatchRepository commands;
    private final ObjectMapper objectMapper;
    private final RealtimeHub realtime;
    private final DecisionTraceService traces;

    public ManualDispatchResultService(ManualDispatchRepository commands,
                                       ObjectMapper objectMapper, RealtimeHub realtime,
                                       DecisionTraceService traces) {
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.realtime = realtime;
        this.traces = traces;
    }

    public void handle(String topic, byte[] payload) {
        try {
            Matcher matcher = TOPIC.matcher(topic);
            if (!matcher.matches()) throw new IllegalArgumentException("unsupported result topic");
            if (payload.length > 8192) throw new IllegalArgumentException("result payload too large");
            JsonNode json = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
            String commandId = requiredText(json, "commandId");
            String simulationId = requiredText(json, "simulationId");
            String targetNode = requiredText(json, "targetNode");
            String status = requiredText(json, "status");
            int vehicleId = json.path("vehicleId").asInt(-1);
            if (!matcher.group(1).equals(simulationId) || !matcher.group(2).equals(commandId)) {
                throw new IllegalArgumentException("result topic/payload identity mismatch");
            }
            if (!TERMINAL.contains(status)) throw new IllegalArgumentException("unsupported result status");
            if (vehicleId < 0) throw new IllegalArgumentException("invalid vehicleId");
            String message = json.path("message").asText("");
            long completedAtMs = json.path("executedAtMs").asLong(System.currentTimeMillis());
            Optional<ManualDispatchCommand> updated = commands.complete(
                    commandId, simulationId, vehicleId, targetNode, status, message, completedAtMs);
            if (updated.isEmpty()) {
                log.warn("Ignored unmatched/duplicate manual-dispatch result topic={}", topic);
                return;
            }
            realtime.broadcast(RealtimeEvent.of("manualDispatch", simulationId, updated.get()));
            traces.acknowledge(commandId, status, message, completedAtMs);
        } catch (Exception exception) {
            log.warn("Rejected manual-dispatch result topic={} payload={} reason={}", topic,
                    new String(payload, StandardCharsets.UTF_8), exception.getMessage());
        }
    }

    private static String requiredText(JsonNode json, String field) {
        String value = json.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("missing " + field);
        return value;
    }
}
