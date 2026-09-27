package com.mine.dispatch.web;

import com.mine.dispatch.capability.monitoring.IngestionMetrics;
import com.mine.dispatch.capability.storage.DatabaseRepository;
import com.mine.dispatch.capability.vehicledata.MqttIngestionClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private final MqttIngestionClient mqtt;
    private final DatabaseRepository database;
    private final IngestionMetrics metrics;
    private final RealtimeHub realtimeHub;

    public HealthController(MqttIngestionClient mqtt,
                            DatabaseRepository database,
                            IngestionMetrics metrics,
                            RealtimeHub realtimeHub) {
        this.mqtt = mqtt;
        this.database = database;
        this.metrics = metrics;
        this.realtimeHub = realtimeHub;
    }

    @GetMapping("/health")
    public Map<String, Object> health() throws SQLException {
        boolean databaseHealthy = database.healthy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", databaseHealthy ? "UP" : "DEGRADED");
        result.put("mqttConnected", mqtt.isConnected());
        result.put("mqttLastError", mqtt.lastError());
        result.put("databaseHealthy", databaseHealthy);
        result.put("webSocketClients", realtimeHub.clientCount());
        result.put("ingestion", metrics.snapshot());
        result.put("storage", database.counts());
        result.put("serverTimeMs", System.currentTimeMillis());
        return result;
    }
}
