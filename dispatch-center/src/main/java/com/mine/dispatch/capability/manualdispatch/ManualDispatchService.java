package com.mine.dispatch.capability.manualdispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.capability.decision.DecisionTraceService;
import com.mine.dispatch.capability.vehicledata.LatestStateStore;
import com.mine.dispatch.capability.vehicledata.MqttIngestionClient;
import com.mine.dispatch.model.*;
import com.mine.dispatch.web.RealtimeHub;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.*;

@Service
public class ManualDispatchService {
    private static final long ACTIVE_COMMAND_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final Set<String> LOAD_TARGETS = Set.of(
            "ShovelOreHigh1", "ShovelOreHigh2", "ShovelOreLow", "ShovelWaste");
    private static final Set<String> DUMP_TARGETS = Set.of("Crusher", "dumpNode");

    private final LatestStateStore states;
    private final DispatchProperties properties;
    private final ManualDispatchRepository commands;
    private final MqttIngestionClient mqtt;
    private final ObjectMapper objectMapper;
    private final RealtimeHub realtime;
    private final DecisionTraceService traces;

    public ManualDispatchService(LatestStateStore states, DispatchProperties properties,
                                 ManualDispatchRepository commands, MqttIngestionClient mqtt,
                                 ObjectMapper objectMapper, RealtimeHub realtime,
                                 DecisionTraceService traces) {
        this.states = states;
        this.properties = properties;
        this.commands = commands;
        this.mqtt = mqtt;
        this.objectMapper = objectMapper;
        this.realtime = realtime;
        this.traces = traces;
    }

    public ManualDispatchCommand create(String simulationId, ManualDispatchRequest request) {
        if (!simulationId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw problem(HttpStatus.BAD_REQUEST, "invalid_simulation_id", "仿真实例 ID 不合法");
        }
        LatestStateStore.SimulationData data = states.data(simulationId);
        VehicleStateView vehicle = data.vehicles().stream()
                .filter(value -> value.vehicleId() == request.vehicleId()).findFirst()
                .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "vehicle_not_found", "车辆不存在于当前仿真实例"));
        long now = System.currentTimeMillis();
        if (now - vehicle.receivedAtMs() > properties.getStaleness().getVehicleMillis()) {
            throw problem(HttpStatus.CONFLICT, "vehicle_stale", "车辆状态已失联，禁止下发改派");
        }
        String phase = vehicle.statechartState();
        boolean travelling = phase.startsWith("PrepareRoute") || phase.startsWith("MoveSegment")
                || phase.startsWith("ArriveSegmentEnd") || phase.startsWith("CheckDestination")
                || phase.startsWith("TravelToLoadSite") || phase.startsWith("TravelToDumpSite")
                || vehicle.speedMps()>0.1;
        if (!travelling) {
            throw problem(HttpStatus.CONFLICT, "vehicle_not_travelling", "车辆当前不在行驶路段，不能改派");
        }
        MapStateView map = data.map();
        if (map == null) throw problem(HttpStatus.CONFLICT, "map_unavailable", "尚未收到 MapState");
        String target = request.targetNode();
        if (vehicle.destinationNode().equals(target)) {
            throw problem(HttpStatus.CONFLICT, "already_targeting", "车辆当前目的地已是该目标点");
        }
        Set<String> allowed = vehicle.loaded() ? DUMP_TARGETS : LOAD_TARGETS;
        if (!allowed.contains(target)) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "target_incompatible",
                    vehicle.loaded() ? "载重车辆只能改派到卸载点" : "空载车辆只能改派到装载点");
        }
        if (map.nodes().stream().noneMatch(node -> node.nodeId().equals(target))) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "target_not_in_map", "目标点不在当前 MapState 中");
        }
        if (!routeExists(map, vehicle.currentNode(), target)) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "route_unavailable", "当前节点到目标点没有可用路线");
        }

        // QoS 1 only confirms delivery to the broker. With a clean MQTT session an
        // offline/restarted AnyLogic client cannot acknowledge an old non-retained
        // command, so do not let that orphan lock the vehicle forever.
        expireStaleActive(simulationId, vehicle.vehicleId(), now);

        ManualDispatchCommand command = new ManualDispatchCommand(
                UUID.randomUUID().toString(), simulationId, vehicle.vehicleId(), target,
                vehicle.destinationNode(), now, null, null, "CREATED", "Awaiting MQTT publish");
        try {
            commands.insert(command);
        } catch (SQLException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("UNIQUE")) {
                throw problem(HttpStatus.CONFLICT, "active_command_exists", "该车辆已有未完成的人工改派指令");
            }
            throw storage(exception);
        }

        Map<String, Object> inputSummary = new LinkedHashMap<>();
        inputSummary.put("vehicleSequence", vehicle.sequence());
        inputSummary.put("vehicleState", vehicle.statechartState());
        inputSummary.put("loaded", vehicle.loaded());
        inputSummary.put("currentNode", vehicle.currentNode());
        inputSummary.put("previousDestinationNode", vehicle.destinationNode());
        inputSummary.put("requestedTargetNode", target);
        traces.create(simulationId, "TASK", "MANUAL_DISPATCH", vehicle.vehicleId(),
                shovelId(target), "OPERATOR_MANUAL_REROUTE", inputSummary,
                Map.of("targetNode", target, "modelUsed", false), null,
                "车辆在线且处于行驶态；载荷与目标兼容；目标存在；路线可达",
                command.commandId(), commandTopic(command));

        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "commandId", command.commandId(),
                    "simulationId", command.simulationId(),
                    "vehicleId", command.vehicleId(),
                    "targetNode", command.targetNode(),
                    "requestedAtMs", command.requestedAtMs()));
            mqtt.publish(commandTopic(command), payload, 1, false);
            command = commands.markSent(command.commandId(), System.currentTimeMillis());
            traces.published(command.commandId());
        } catch (MqttException exception) {
            command = markPublishFailed(command, "MQTT publish failed: " + exception.getMessage());
            traces.publishFailed(command.commandId(), command.statusMessage());
        } catch (Exception exception) {
            command = markPublishFailed(command, "Command serialization/persistence failed: " + exception.getMessage());
            traces.publishFailed(command.commandId(), command.statusMessage());
        }
        broadcast(command);
        return command;
    }

    public List<ManualDispatchCommand> list(String simulationId, Integer vehicleId, int limit) {
        try {
            long now = System.currentTimeMillis();
            commands.expireStaleActive(simulationId, vehicleId,
                    now - ACTIVE_COMMAND_TIMEOUT_MS, now);
            return commands.list(simulationId, vehicleId, Math.max(1, Math.min(200, limit)));
        } catch (SQLException exception) {
            throw storage(exception);
        }
    }

    private void expireStaleActive(String simulationId, int vehicleId, long now) {
        try {
            commands.expireStaleActive(simulationId, vehicleId,
                    now - ACTIVE_COMMAND_TIMEOUT_MS, now);
        } catch (SQLException exception) {
            throw storage(exception);
        }
    }

    private ManualDispatchCommand markPublishFailed(ManualDispatchCommand command, String message) {
        try {
            return commands.markPublishFailed(command.commandId(), message);
        } catch (SQLException exception) {
            throw storage(exception);
        }
    }

    private void broadcast(ManualDispatchCommand command) {
        realtime.broadcast(RealtimeEvent.of("manualDispatch", command.simulationId(), command));
    }

    private static boolean routeExists(MapStateView map, String start, String target) {
        if (start == null || start.isBlank()) return false;
        if (start.equals(target)) return true;
        Map<String, List<String>> graph = new HashMap<>();
        for (RoadSegmentView segment : map.segments()) {
            if (!segment.closed()) graph.computeIfAbsent(segment.fromNodeId(), ignored -> new ArrayList<>())
                    .add(segment.toNodeId());
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            if (!visited.add(node)) continue;
            for (String next : graph.getOrDefault(node, List.of())) {
                if (next.equals(target)) return true;
                queue.addLast(next);
            }
        }
        return false;
    }

    private static String commandTopic(ManualDispatchCommand command) {
        return "mine/sim/" + command.simulationId() + "/command/manual-dispatch/" + command.commandId();
    }

    private static Integer shovelId(String target) {
        return switch (target) {
            case "ShovelOreHigh1" -> 0;
            case "ShovelOreHigh2" -> 1;
            case "ShovelOreLow" -> 2;
            case "ShovelWaste" -> 3;
            default -> null;
        };
    }

    private static ManualDispatchException problem(HttpStatus status, String code, String message) {
        return new ManualDispatchException(status, code, message);
    }

    private static ManualDispatchException storage(Exception exception) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "command_storage_failed", exception.getMessage());
    }
}
