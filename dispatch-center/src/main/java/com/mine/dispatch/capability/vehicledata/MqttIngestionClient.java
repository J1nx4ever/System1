package com.mine.dispatch.capability.vehicledata;

import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.capability.production.TlsSocketFactoryFactory;
import com.mine.dispatch.capability.manualdispatch.ManualDispatchResultService;
import com.mine.dispatch.capability.decision.DecisionAckService;
import com.mine.dispatch.capability.decision.InferenceDecisionService;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MqttIngestionClient implements SmartLifecycle, MqttCallbackExtended {
    private static final Logger log = LoggerFactory.getLogger(MqttIngestionClient.class);

    private final DispatchProperties properties;
    private final FieldMessageIngestionService ingestion;
    private final ObjectProvider<ManualDispatchResultService> dispatchResults;
    private final ObjectProvider<InferenceDecisionService> inferenceDecisions;
    private final ObjectProvider<DecisionAckService> decisionAcks;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile MqttAsyncClient client;
    private volatile String lastError = "";
    private com.mine.dispatch.capability.tracking.ProductionEventService productionEvents;
    private com.mine.dispatch.capability.storage.DatabaseRepository productionStorage;

    @org.springframework.beans.factory.annotation.Autowired
    public void setProductionEvents(com.mine.dispatch.capability.tracking.ProductionEventService events,
                                    com.mine.dispatch.capability.storage.DatabaseRepository storage) {
        productionEvents = events;
        productionStorage = storage;
    }

    public MqttIngestionClient(DispatchProperties properties,
                               FieldMessageIngestionService ingestion,
                               ObjectProvider<ManualDispatchResultService> dispatchResults,
                               ObjectProvider<InferenceDecisionService> inferenceDecisions,
                               ObjectProvider<DecisionAckService> decisionAcks) {
        this.properties = properties;
        this.ingestion = ingestion;
        this.dispatchResults = dispatchResults;
        this.inferenceDecisions = inferenceDecisions;
        this.decisionAcks = decisionAcks;
    }

    @Override
    public void start() {
        if (!properties.getMqtt().isEnabled()) {
            log.info("MQTT ingestion is disabled");
            return;
        }
        running.set(true);
        ensureConnected();
    }

    @Scheduled(fixedDelay = 5000)
    public void retryInitialConnection() {
        if (running.get() && !isConnected()) ensureConnected();
    }

    private void ensureConnected() {
        if (!connecting.compareAndSet(false, true)) return;
        try {
            DispatchProperties.Mqtt mqtt = properties.getMqtt();
            if (mqtt.getUsername() != null && !mqtt.getUsername().isBlank()
                    && (mqtt.getPassword() == null || mqtt.getPassword().isBlank())) {
                throw new IllegalStateException("MQTT_PASSWORD is required when MQTT_USERNAME is set");
            }
            if (client == null) {
                client = new MqttAsyncClient(mqtt.getBrokerUri(), clientId(mqtt),
                        new MemoryPersistence());
                client.setCallback(this);
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setKeepAliveInterval(mqtt.getKeepAliveSeconds());
            options.setConnectionTimeout(mqtt.getConnectionTimeoutSeconds());
            options.setMaxInflight(mqtt.getMaxInflight());
            if (mqtt.getUsername() != null && !mqtt.getUsername().isBlank()) {
                options.setUserName(mqtt.getUsername());
                options.setPassword(mqtt.getPassword().toCharArray());
            }
            if (mqtt.getBrokerUri().startsWith("ssl://")) {
                options.setHttpsHostnameVerificationEnabled(true);
                if (mqtt.getCaFile() != null && !mqtt.getCaFile().isBlank()) {
                    options.setSocketFactory(TlsSocketFactoryFactory.fromCaFile(mqtt.getCaFile()));
                }
            }
            client.connect(options).waitForCompletion(
                    Math.max(5000, mqtt.getConnectionTimeoutSeconds() * 1000L + 1000));
            lastError = "";
        } catch (Exception exception) {
            lastError = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            log.warn("MQTT connection failed (will retry): {}", lastError);
        } finally {
            connecting.set(false);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(new String[] {
                    properties.getMqtt().getTopic(),
                    properties.getMqtt().getResultTopic(),
                    properties.getMqtt().getInferenceTriggerTopic(),
                    properties.getMqtt().getDecisionResultTopic(),
                    "mine/sim/+/event/production/+"
            }, new int[] {1, 1, 1, 1, 1}).waitForCompletion(5000);
            lastError = "";
            log.info("MQTT {} to {}; state={}, manualResult={}, inferenceTrigger={}, decisionResult={}",
                    reconnect ? "reconnected" : "connected", serverURI,
                    properties.getMqtt().getTopic(),
                    properties.getMqtt().getResultTopic(),
                    properties.getMqtt().getInferenceTriggerTopic(),
                    properties.getMqtt().getDecisionResultTopic());
        } catch (MqttException exception) {
            lastError = exception.getMessage();
            log.error("MQTT subscription failed", exception);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        lastError = cause == null ? "connection lost" : cause.getMessage();
        log.warn("MQTT connection lost: {}", lastError);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (topic.contains("/event/production/")) {
            try {
                productionEvents.handle(topic, message.getPayload(), message.isRetained());
            } catch (Exception exception) {
                log.warn("Production event rejected topic={} reason={}", topic, exception.getMessage());
                try {
                    productionStorage.rejection(topic, exception.getMessage(), message.getPayload().length);
                } catch (Exception storageFailure) {
                    log.error("Production rejection persistence failed", storageFailure);
                }
            }
            return;
        }
        if (topic.contains("/result/manual-dispatch/")) {
            dispatchResults.getObject().handle(topic, message.getPayload());
        } else if (topic.contains("/result/decision/")) {
            decisionAcks.getObject().handle(topic, message.getPayload());
        } else if (topic.contains("/trigger/")) {
            inferenceDecisions.getObject().handle(topic, message.getPayload());
        } else {
            ingestion.handle(topic, message.getPayload(), message.getQos(), message.isRetained());
        }
    }

    public void publish(String topic, byte[] payload, int qos, boolean retained) throws MqttException {
        MqttAsyncClient current = client;
        if (current == null || !current.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        current.publish(topic, payload, qos, retained).waitForCompletion(5000);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    public boolean isConnected() {
        MqttAsyncClient current = client;
        return current != null && current.isConnected();
    }

    public String lastError() { return lastError; }

    @Override
    public void stop() {
        running.set(false);
        MqttAsyncClient current = client;
        if (current == null) return;
        try {
            if (current.isConnected()) current.disconnect().waitForCompletion(3000);
            current.close();
        } catch (MqttException exception) {
            log.warn("MQTT shutdown failed: {}", exception.getMessage());
        }
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    private static String clientId(DispatchProperties.Mqtt mqtt) {
        if (mqtt.getClientId() != null && !mqtt.getClientId().isBlank()) return mqtt.getClientId();
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9_-]", "-");
        } catch (Exception ignored) {
            host = "local";
        }
        return "dispatch-center-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

