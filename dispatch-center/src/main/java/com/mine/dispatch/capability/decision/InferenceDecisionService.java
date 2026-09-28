package com.mine.dispatch.capability.decision;

import DRL.grpc.DispatchRequest;
import DRL.grpc.DispatchResponse;
import DRL.grpc.DispatchServiceGrpc;
import Fusion.client.SpeedInferenceClient;
import Fusion.grpc.Action;
import Fusion.grpc.ActionDict;
import Fusion.grpc.Observation;
import Fusion.grpc.ObservationDict;
import Fusion.grpc.StateData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.capability.vehicledata.LatestStateStore;
import com.mine.dispatch.capability.vehicledata.MqttIngestionClient;
import com.mine.dispatch.model.MapStateView;
import com.mine.dispatch.model.ShovelStateView;
import com.mine.dispatch.model.VehicleStateView;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InferenceDecisionService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InferenceDecisionService.class);
    private static final Pattern TRIGGER_TOPIC = Pattern.compile(
            "^mine/sim/([A-Za-z0-9_-]{1,64})/trigger/(dispatch|speed)$");
    private static final String[] LOAD_TARGETS = {
            "ShovelOreHigh1", "ShovelOreHigh2", "ShovelOreLow", "ShovelWaste"
    };

    private final DispatchProperties properties;
    private final LatestStateStore states;
    private final MqttIngestionClient mqtt;
    private final DecisionTraceService traces;
    private final ObjectMapper json;
    private final ManagedChannel dispatchChannel;
    private final DispatchServiceGrpc.DispatchServiceBlockingStub dispatchStub;
    private final SpeedInferenceClient speedClient;
    private final DispatchOnnxPolicy dispatchOnnxPolicy;
    private final SpeedOnnxPolicy speedOnnxPolicy;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "inference-decision-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<SpeedTrigger> latestSpeedTrigger = new AtomicReference<>();
    private final AtomicBoolean speedWorkerRunning = new AtomicBoolean();

    public InferenceDecisionService(DispatchProperties properties, LatestStateStore states,
                                    MqttIngestionClient mqtt, DecisionTraceService traces,
                                    ObjectMapper json) {
        this.properties = properties;
        this.states = states;
        this.mqtt = mqtt;
        this.traces = traces;
        this.json = json;
        DispatchProperties.Inference config = properties.getInference();
        if (!config.isEnabled()) {
            dispatchChannel = null;
            dispatchStub = null;
            speedClient = null;
            dispatchOnnxPolicy = null;
            speedOnnxPolicy = null;
        } else if ("onnx".equalsIgnoreCase(config.getMode())) {
            dispatchOnnxPolicy = new DispatchOnnxPolicy(config.getDispatchModelPath());
            speedOnnxPolicy = new SpeedOnnxPolicy(config.getSpeedModelPath());
            dispatchChannel = null;
            dispatchStub = null;
            speedClient = null;
            log.info("Inference mode=onnx dispatchModel={} speedModel={}",
                    config.getDispatchModelPath(), config.getSpeedModelPath());
        } else if ("grpc".equalsIgnoreCase(config.getMode())) {
            dispatchOnnxPolicy = null;
            speedOnnxPolicy = null;
            dispatchChannel = ManagedChannelBuilder.forAddress(
                    config.getDispatchHost(), config.getDispatchPort()).usePlaintext().build();
            dispatchStub = DispatchServiceGrpc.newBlockingStub(dispatchChannel);
            speedClient = new SpeedInferenceClient(
                    config.getSpeedHost(), config.getSpeedPort(), config.getSpeedTimeoutMillis());
            log.info("Inference mode=grpc dispatch={}:{} speed={}:{}",
                    config.getDispatchHost(), config.getDispatchPort(),
                    config.getSpeedHost(), config.getSpeedPort());
        } else {
            throw new IllegalArgumentException("unsupported inference mode: " + config.getMode());
        }
    }

    public void handle(String topic, byte[] payload) {
        Matcher matcher = TRIGGER_TOPIC.matcher(topic);
        if (!matcher.matches()) {
            log.warn("Rejected inference trigger topic={}", topic);
            return;
        }
        String simulationId = matcher.group(1);
        String type = matcher.group(2);
        byte[] copy = Arrays.copyOf(payload, payload.length);
        if ("speed".equals(type)) {
            enqueueLatestSpeed(simulationId, copy);
            return;
        }
        executor.execute(() -> {
            try {
                if (!properties.getInference().isEnabled()) {
                    throw new IllegalStateException("inference gateway is disabled");
                }
                handleDispatch(simulationId, copy);
            } catch (Exception exception) {
                log.warn("Inference trigger failed simulation={} type={} reason={}",
                        simulationId, type, exception.getMessage());
            }
        });
    }

    private void enqueueLatestSpeed(String simulationId, byte[] payload) {
        // 仿真可以快于墙钟时间。速度控制只需要最新完整观测，旧帧不能排队补算。
        latestSpeedTrigger.set(new SpeedTrigger(simulationId, payload));
        startSpeedWorker();
    }

    private void startSpeedWorker() {
        if (!speedWorkerRunning.compareAndSet(false, true)) return;
        executor.execute(this::drainLatestSpeed);
    }

    private void drainLatestSpeed() {
        try {
            while (true) {
                SpeedTrigger trigger = latestSpeedTrigger.getAndSet(null);
                if (trigger == null) return;
                try {
                    if (!properties.getInference().isEnabled()) {
                        throw new IllegalStateException("inference gateway is disabled");
                    }
                    handleSpeed(trigger.simulationId(), trigger.payload());
                } catch (Exception exception) {
                    log.warn("Inference trigger failed simulation={} type=speed reason={}",
                            trigger.simulationId(), exception.getMessage());
                }
            }
        } finally {
            speedWorkerRunning.set(false);
            // 覆盖发生在退出临界区时，重新拉起一次；CAS 保证仍只有一个 worker。
            if (latestSpeedTrigger.get() != null) startSpeedWorker();
        }
    }

    private void handleDispatch(String simulationId, byte[] payload) throws Exception {
        DispatchRequest request = DispatchRequest.parseFrom(payload);
        validateDispatchRequest(request);
        VehicleStateView vehicle = vehicle(simulationId, request.getTruckId());
        var evidence = com.mine.dispatch.capability.tracking.DispatchEvidence.capture(request, vehicle,
                states.data(simulationId).shovels(), states.data(simulationId).map(),
                System.getenv("DISPATCH_MODEL_VERSION"));
        int action;
        double modelValue;
        List<Double> modelLogits = new ArrayList<>();
        long modelLatencyUs;
        if (dispatchOnnxPolicy != null) {
            DispatchOnnxPolicy.Prediction prediction =
                    dispatchOnnxPolicy.predict(request.getObs().getValuesList());
            action = prediction.action();
            modelValue = prediction.value();
            for (float logit : prediction.logits()) modelLogits.add((double) logit);
            modelLatencyUs = prediction.latencyUs();
        } else {
            DispatchResponse response = dispatchStub
                    .withDeadlineAfter(properties.getInference().getDispatchTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .getNextDestination(request);
            action = response.getAction();
            if (!response.getHasAction() && request.getActionRequired()) {
                throw new IllegalStateException("dispatch model returned no action");
            }
            if (!response.getEpisodeId().isBlank()
                    && !response.getEpisodeId().equals(request.getEpisodeId())) {
                throw new IllegalStateException("dispatch episode correlation mismatch");
            }
            if (response.getDecisionSequence() != 0
                    && response.getDecisionSequence() != request.getDecisionSequence()) {
                throw new IllegalStateException("dispatch sequence correlation mismatch");
            }
            modelValue = response.getValue();
            modelLogits.addAll(response.getLogitsList());
            modelLatencyUs = response.getLatencyUs();
        }
        if (action < 0 || action >= LOAD_TARGETS.length) {
            throw new IllegalStateException("dispatch action out of range: " + action);
        }
        String targetNode = LOAD_TARGETS[action];
        LatestStateStore.SimulationData data = states.data(simulationId);
        MapStateView map = data.map();
        if (map == null || map.nodes().stream().noneMatch(node -> node.nodeId().equals(targetNode))) {
            throw new IllegalStateException("model target is absent from current map: " + targetNode);
        }
        ShovelStateView shovel = data.shovels().stream()
                .filter(item -> item.shovelId() == action).findFirst().orElse(null);
        if (shovel != null && !shovel.available()) {
            throw new IllegalStateException("model target shovel is unavailable: " + action);
        }

        String commandId = UUID.randomUUID().toString();
        String commandTopic = "mine/sim/" + simulationId + "/command/auto-dispatch/" + commandId;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requestId", request.getRequestId());
        input.put("episodeId", request.getEpisodeId());
        input.put("decisionSequence", request.getDecisionSequence());
        input.put("truckId", request.getTruckId());
        input.put("schemaId", request.getObs().getSchemaId());
        input.put("values", request.getObs().getValuesList());
        input.put("simTimeSeconds", request.getSimTimeSeconds());
        input.put("evidenceSnapshot", evidence);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", action);
        output.put("targetNode", targetNode);
        output.put("value", modelValue);
        output.put("logits", modelLogits);
        output.put("latencyUs", modelLatencyUs);
        traces.create(simulationId, "TASK", "DRL_DISPATCH", vehicle.vehicleId(), action,
                "UNLOAD_COMPLETE", input, output, null,
                "27维观测有限值；action范围0..3；目标存在于地图；铲装点可用",
                commandId, commandTopic);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("commandId", commandId);
        command.put("simulationId", simulationId);
        command.put("requestId", request.getRequestId());
        command.put("vehicleId", vehicle.vehicleId());
        command.put("action", action);
        command.put("targetNode", targetNode);
        command.put("issuedAtMs", System.currentTimeMillis());
        publish(commandTopic, commandId, command, true);
    }

    private void handleSpeed(String simulationId, byte[] payload) throws Exception {
        ObservationDict observations = ObservationDict.parseFrom(payload);
        if (observations.getObservationsCount() == 0) {
            throw new IllegalArgumentException("speed observation batch is empty");
        }
        Map<String, Observation> byAgent = new HashMap<>();
        for (Observation observation : observations.getObservationsList()) {
            if (observation.getAgentId().isBlank() || !observation.hasState()) {
                throw new IllegalArgumentException("speed observation agent/state missing");
            }
            if (byAgent.put(observation.getAgentId(), observation) != null) {
                throw new IllegalArgumentException("duplicate speed observation agent");
            }
        }
        long inferenceStartedAtNs = System.nanoTime();
        ActionDict actions = speedOnnxPolicy != null
            ? speedOnnxPolicy.predict(observations)
            : speedClient.getAccelerations(observations);
        long inferenceLatencyUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - inferenceStartedAtNs);
        for (Action action : actions.getActionsList()) {
            Observation observation = byAgent.get(action.getAgentId());
            if (observation == null) throw new IllegalStateException("action has no matching observation");
            if (action.getActionCount() != 1) throw new IllegalStateException("action must contain one acceleration");
            double acceleration = action.getAction(0);
            DispatchProperties.Inference config = properties.getInference();
            if (!Double.isFinite(acceleration)
                    || acceleration < config.getMinAccelerationMps2()
                    || acceleration > config.getMaxAccelerationMps2()) {
                throw new IllegalStateException("acceleration outside configured safety boundary: " + acceleration);
            }
            int vehicleId = parseAgentId(action.getAgentId());
            VehicleStateView vehicle = vehicle(simulationId, vehicleId);
            StateData state = observation.getState();
            String commandId = UUID.randomUUID().toString();
            String commandTopic = "mine/sim/" + simulationId + "/command/speed/" + commandId;
            Map<String, Object> input = speedInput(observation);
            Map<String, Object> output = Map.of("agentId", action.getAgentId(),
                    "accelerationMps2", acceleration, "latencyUs", inferenceLatencyUs);
            boolean recorded = traces.recordSpeed(simulationId, vehicleId, state.getHasFront(),
                    state.getHasConflict(), acceleration, input, output, commandId, commandTopic);
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("commandId", commandId);
            command.put("simulationId", simulationId);
            command.put("vehicleId", vehicleId);
            command.put("agentId", action.getAgentId());
            command.put("accelerationMps2", acceleration);
            command.put("sourceSequence", vehicle.sequence());
            command.put("validForMs", 1000);
            command.put("issuedAtMs", System.currentTimeMillis());
            publish(commandTopic, commandId, command, recorded);
        }
    }

    private void publish(String topic, String commandId, Object command, boolean recorded) throws Exception {
        try {
            mqtt.publish(topic, json.writeValueAsBytes(command), 1, false);
            if (recorded) traces.published(commandId);
        } catch (Exception exception) {
            traces.publishFailed(commandId, exception.getMessage());
            throw exception;
        }
    }

    private static void validateDispatchRequest(DispatchRequest request) {
        if (!request.hasObs()) throw new IllegalArgumentException("dispatch observation missing");
        if (!"dispatch.v1-27".equals(request.getObs().getSchemaId())) {
            throw new IllegalArgumentException("unsupported dispatch schema");
        }
        if (request.getObs().getValuesCount() != 27) {
            throw new IllegalArgumentException("dispatch observation must contain 27 values");
        }
        for (float value : request.getObs().getValuesList()) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("dispatch observation is non-finite");
            if (value < -1.000001f || value > 1.000001f) {
                throw new IllegalArgumentException("dispatch observation outside [-1,1]");
            }
        }
        if (request.getTruckId() < 0) throw new IllegalArgumentException("invalid truck id");
    }

    private VehicleStateView vehicle(String simulationId, int vehicleId) {
        return states.data(simulationId).vehicles().stream()
                .filter(value -> value.vehicleId() == vehicleId).findFirst()
                .orElseThrow(() -> new IllegalStateException("vehicle absent from current state: " + vehicleId));
    }

    private static int parseAgentId(String value) {
        if (value == null || !value.matches("agent_[0-9]+")) {
            throw new IllegalArgumentException("invalid agent id: " + value);
        }
        return Integer.parseInt(value.substring("agent_".length()));
    }

    private static Map<String, Object> speedInput(Observation observation) {
        StateData state = observation.getState();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("agentId", observation.getAgentId());
        value.put("egoAgentId", state.getEgoAgentId());
        value.put("targetRoadNode", state.getTargetRoadNode());
        value.put("egoSpeedFeature", state.getEgoSpeed());
        value.put("laneProgressFeature", state.getDistToInt());
        value.put("ttaFeature", state.getTtaEgo());
        value.put("previousAcceleration", state.getPrevCmdAccel());
        value.put("hasFront", state.getHasFront());
        value.put("frontAgentId", state.getFrontAgentId());
        value.put("frontSpeedFeature", state.getFrontSpeed());
        value.put("frontDistanceFeature", state.getFrontDist());
        value.put("frontTtcFeature", state.getFrontTtc());
        value.put("hasRear", state.getHasRear());
        value.put("rearAgentId", state.getRearAgentId());
        value.put("rearSpeedFeature", state.getRearSpeed());
        value.put("rearDistanceFeature", state.getRearDist());
        value.put("hasConflict", state.getHasConflict());
        value.put("conflictVehicleCount", state.getConflictVehiclesCount());
        return value;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        if (speedClient != null) speedClient.close();
        if (speedOnnxPolicy != null) speedOnnxPolicy.close();
        if (dispatchOnnxPolicy != null) dispatchOnnxPolicy.close();
        if (dispatchChannel != null) dispatchChannel.shutdownNow();
    }

    private record SpeedTrigger(String simulationId, byte[] payload) {}
}

