package com.mine.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.monitoring.IngestionMetrics;
import com.mine.dispatch.capability.monitoring.StateViewService;
import com.mine.dispatch.capability.manualdispatch.ManualDispatchRepository;
import com.mine.dispatch.capability.manualdispatch.ManualDispatchResultService;
import com.mine.dispatch.capability.storage.AsyncHistoryWriter;
import com.mine.dispatch.capability.storage.DatabaseRepository;
import com.mine.dispatch.capability.vehicledata.FieldMessageIngestionService;
import com.mine.dispatch.model.SimulationSnapshot;
import com.mine.dispatch.model.ManualDispatchCommand;
import mine.field.v1.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndIntegrationTest {
    private static final Path DATABASE = temporaryDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dispatch.mqtt.enabled", () -> "false");
        registry.add("dispatch.security.enabled", () -> "false");
        registry.add("dispatch.storage.jdbc-url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("dispatch.staleness.vehicle-millis", () -> "150");
        registry.add("dispatch.staleness.shovel-millis", () -> "150");
        registry.add("dispatch.staleness.simulation-millis", () -> "300");
    }

    @LocalServerPort
    private int port;

    @Autowired private FieldMessageIngestionService ingestion;
    @Autowired private StateViewService views;
    @Autowired private AsyncHistoryWriter historyWriter;
    @Autowired private DatabaseRepository database;
    @Autowired private IngestionMetrics metrics;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ManualDispatchRepository dispatchCommands;
    @Autowired private ManualDispatchResultService dispatchResults;

    @Test
    void realProtobufFlowsThroughStatePersistenceRestAndWebSocket() throws Exception {
        MapState map = MapState.newBuilder()
                .setSimulationId("SIM_001").setSequence(1).setSimTimeSeconds(.5)
                .setImageFile("image2.png").setImageWidthPx(1920).setImageHeightPx(957)
                .addNodes(RoadNode.newBuilder().setNodeId("dumpNode").setX(100).setY(200))
                .addNodes(RoadNode.newBuilder().setNodeId("ShovelOreHigh1").setX(1125).setY(430))
                .addNodes(RoadNode.newBuilder().setNodeId("ShovelOreHigh2").setX(1300).setY(360))
                .addSegments(RoadSegment.newBuilder().setSegmentId("dumpNode_intersection2")
                        .setFromNodeId("dumpNode").setToNodeId("ShovelOreHigh1")
                        .setLengthModelUnits(1100).setClosed(false))
                .addSegments(RoadSegment.newBuilder().setSegmentId("dumpNode_ShovelOreHigh2")
                        .setFromNodeId("dumpNode").setToNodeId("ShovelOreHigh2")
                        .setLengthModelUnits(1200).setClosed(false))
                .build();
        VehicleState vehicle = vehicle(1562, 1113.1484, 624.9609);
        ShovelState shovel = ShovelState.newBuilder()
                .setSimulationId("SIM_001").setSequence(1246).setSimTimeSeconds(25.5)
                .setShovelId(0).setShovelName("HG1").setX(1125).setY(430)
                .setAvailable(true).setBusy(false).setWaitingTrucks(0).setTrucksInService(0)
                .setOreGrade(.38).setRemainingTons(100000).setProducesOre(true)
                .build();

        ingestion.handle("mine/sim/SIM_001/state/map", map.toByteArray(), 1, true);
        ingestion.handle("mine/sim/SIM_001/state/vehicle/0", vehicle.toByteArray(), 0, false);
        // A globally smaller sequence for another entity is valid and must be accepted.
        ingestion.handle("mine/sim/SIM_001/state/shovel/0", shovel.toByteArray(), 0, false);

        SimulationSnapshot snapshot = views.snapshot("SIM_001");
        assertThat(snapshot.map().imageWidthPx()).isEqualTo(1920);
        assertThat(snapshot.vehicles()).singleElement().satisfies(value -> {
            assertThat(value.vehicleId()).isZero();
            assertThat(value.destinationNode()).isEqualTo("ShovelOreHigh1");
            assertThat(value.speedMps()).isEqualTo(6.944444444444445);
        });
        assertThat(snapshot.shovels()).singleElement().satisfies(value -> {
            assertThat(value.shovelId()).isZero();
            assertThat(value.shovelName()).isEqualTo("HG1");
        });

        assertThat(historyWriter.flush(Duration.ofSeconds(5))).isTrue();
        assertThat(database.counts()).containsEntry("mapHistory", 1L)
                .containsEntry("vehicleHistory", 1L)
                .containsEntry("shovelHistory", 1L);

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> rest = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port +
                                "/api/v1/simulations/SIM_001/snapshot"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rest.statusCode()).isEqualTo(200);
        JsonNode restJson = objectMapper.readTree(rest.body());
        assertThat(restJson.at("/vehicles/0/vehicleId").asInt()).isZero();
        assertThat(restJson.at("/shovels/0/shovelName").asText()).isEqualTo("HG1");

        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        ingestion.handle("mine/sim/SIM_001/state/vehicle/0",
                vehicle(1563, 1116.0, 626.0).toByteArray(), 0, false);

        HttpResponse<String> dispatchResponse = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port +
                                "/api/v1/simulations/SIM_001/manual-dispatch"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"vehicleId\":0,\"targetNode\":\"ShovelOreHigh2\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(dispatchResponse.statusCode()).isEqualTo(503);
        JsonNode dispatchJson = objectMapper.readTree(dispatchResponse.body());
        assertThat(dispatchJson.path("commandId").asText()).isNotBlank();
        assertThat(dispatchJson.path("status").asText()).isEqualTo("PUBLISH_FAILED");
        assertThat(dispatchCommands.list("SIM_001", 0, 10)).singleElement()
                .satisfies(value -> assertThat(value.targetNode()).isEqualTo("ShovelOreHigh2"));
        HttpResponse<String> decisions = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port +
                                "/api/v1/simulations/SIM_001/vehicles/0/decisions"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(decisions.statusCode()).isEqualTo(200);
        JsonNode decisionJson = objectMapper.readTree(decisions.body()).get(0);
        assertThat(decisionJson.path("decisionSource").asText()).isEqualTo("MANUAL_DISPATCH");
        assertThat(decisionJson.path("explanation").isNull()).isTrue();
        assertThat(decisionJson.path("ackStatus").asText()).isEqualTo("PUBLISH_FAILED");

        for (String page : new String[] {"/", "/vehicle.html", "/shovel.html"}) {
            HttpResponse<String> terminal = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + page)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(terminal.statusCode()).isEqualTo(200);
        }
        WebSocket socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port +
                        "/ws/state?simulationId=SIM_001"), new QueueingListener(messages))
                .get(5, TimeUnit.SECONDS);
        assertThat(messages.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"snapshot\"");

        ingestion.handle("mine/sim/SIM_001/state/vehicle/0",
                vehicle(1566, 1120.5, 630.25).toByteArray(), 0, false);
        String update = messages.poll(5, TimeUnit.SECONDS);
        assertThat(update).contains("\"type\":\"vehicle\"")
                .contains("\"sequence\":1566")
                .contains("\"x\":1120.5");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);

        Thread.sleep(350);
        assertThat(views.snapshot("SIM_001").online()).isFalse();
        assertThat(views.snapshot("SIM_001").vehicles().get(0).stale()).isTrue();
        assertThat(metrics.snapshot()).containsEntry("received", 5L)
                .containsEntry("accepted", 5L);
    }


    @Test
    void manualDispatchResultMustMatchIdentityAndBeIdempotent() throws Exception {
        long now = System.currentTimeMillis();
        ManualDispatchCommand command = new ManualDispatchCommand(
                "11111111-1111-1111-1111-111111111111", "SIM_001", 0,
                "ShovelOreHigh2", "ShovelOreHigh1", now, now, null,
                "SENT", "MQTT command published");
        dispatchCommands.insert(command);

        dispatchResults.handle(
                "mine/sim/SIM_001/result/manual-dispatch/11111111-1111-1111-1111-111111111111",
                ("{\"commandId\":\"11111111-1111-1111-1111-111111111111\"," +
                 "\"simulationId\":\"SIM_001\",\"vehicleId\":0," +
                 "\"targetNode\":\"ShovelOreLow\",\"status\":\"EXECUTED\"}").getBytes());
        assertThat(dispatchCommands.find(command.commandId()).orElseThrow().status()).isEqualTo("SENT");

        byte[] valid = ("{\"commandId\":\"11111111-1111-1111-1111-111111111111\"," +
                "\"simulationId\":\"SIM_001\",\"vehicleId\":0," +
                "\"targetNode\":\"ShovelOreHigh2\",\"status\":\"EXECUTED\"," +
                "\"message\":\"route replaced at intersection2\",\"executedAtMs\":" + now + "}").getBytes();
        dispatchResults.handle(
                "mine/sim/SIM_001/result/manual-dispatch/11111111-1111-1111-1111-111111111111", valid);
        ManualDispatchCommand executed = dispatchCommands.find(command.commandId()).orElseThrow();
        assertThat(executed.status()).isEqualTo("EXECUTED");
        assertThat(executed.statusMessage()).contains("intersection2");

        dispatchResults.handle(
                "mine/sim/SIM_001/result/manual-dispatch/11111111-1111-1111-1111-111111111111", valid);
        assertThat(dispatchCommands.find(command.commandId()).orElseThrow().status()).isEqualTo("EXECUTED");
    }

    private static VehicleState vehicle(long sequence, double x, double y) {
        return VehicleState.newBuilder()
                .setSimulationId("SIM_001").setSequence(sequence).setSimTimeSeconds(32.5)
                .setSentWallTimeMs(System.currentTimeMillis()).setVehicleId(0)
                .setX(x).setY(y).setSpeedMps(6.944444444444445)
                .setLoaded(false).setPayloadTons(0).setCurrentNode("dumpNode")
                .setDestinationNode("ShovelOreHigh1")
                .setCurrentSegmentId("dumpNode_intersection2")
                .setTargetLoadSiteId(0).setTargetDumpSiteId(-1)
                .setStatechartState("MoveSegment").build();
    }

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("dispatch-center-test-").resolve("state.db");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class QueueingListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages;
        private final StringBuilder partial = new StringBuilder();

        private QueueingListener(LinkedBlockingQueue<String> messages) {
            this.messages = messages;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
