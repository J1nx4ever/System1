package com.mine.dispatch.capability.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DecisionAckService {
    private static final Logger log = LoggerFactory.getLogger(DecisionAckService.class);
    private static final Pattern TOPIC = Pattern.compile(
            "^mine/sim/([A-Za-z0-9_-]{1,64})/result/decision/([0-9a-fA-F-]{36})$");
    private static final Set<String> STATUS = Set.of("EXECUTED", "REJECTED", "EXPIRED");
    private final DecisionTraceService traces;
    private final ObjectMapper json;

    public DecisionAckService(DecisionTraceService traces, ObjectMapper json) {
        this.traces = traces;
        this.json = json;
    }

    public void handle(String topic, byte[] payload) {
        try {
            Matcher matcher = TOPIC.matcher(topic);
            if (!matcher.matches()) throw new IllegalArgumentException("unsupported decision result topic");
            if (payload.length > 8192) throw new IllegalArgumentException("decision result too large");
            JsonNode body = json.readTree(new String(payload, StandardCharsets.UTF_8));
            String commandId = required(body, "commandId");
            String simulationId = required(body, "simulationId");
            String status = required(body, "status");
            if (!matcher.group(1).equals(simulationId) || !matcher.group(2).equals(commandId)) {
                throw new IllegalArgumentException("decision result identity mismatch");
            }
            if (!STATUS.contains(status)) throw new IllegalArgumentException("unsupported decision result status");
            traces.acknowledge(commandId, status, body.path("message").asText(""),
                    body.path("executedAtMs").asLong(System.currentTimeMillis()));
        } catch (Exception exception) {
            log.warn("Rejected decision result topic={} reason={}", topic, exception.getMessage());
        }
    }

    private static String required(JsonNode body, String name) {
        String value = body.path(name).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }
}
