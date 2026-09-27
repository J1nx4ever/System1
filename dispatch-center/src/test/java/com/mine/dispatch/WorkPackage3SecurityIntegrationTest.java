package com.mine.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.decision.DecisionTraceService;
import com.mine.dispatch.capability.vehicledata.FieldMessageIngestionService;
import mine.field.v1.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkPackage3SecurityIntegrationTest {
    private static final Path DATABASE = database();
    private static final String USERS = "dispatcher|dispatch-pass|DISPATCHER||;" +
            "truck0|truck-pass|VEHICLE|SIM_AUTH|0;truck1|truck-pass|VEHICLE|SIM_AUTH|1;" +
            "shovel0|shovel-pass|SHOVEL|SIM_AUTH|0;shovel1|shovel-pass|SHOVEL|SIM_AUTH|1";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("dispatch.mqtt.enabled", () -> "false");
        registry.add("dispatch.inference.enabled", () -> "false");
        registry.add("dispatch.storage.jdbc-url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("dispatch.security.bootstrap-users", () -> USERS);
    }

    @LocalServerPort int port;
    @Autowired FieldMessageIngestionService ingestion;
    @Autowired DecisionTraceService traces;
    @Autowired ObjectMapper json;
    HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void state() {
        ingestion.handle("mine/sim/SIM_AUTH/state/map", MapState.newBuilder()
                .setSimulationId("SIM_AUTH").setSequence(1).setSimTimeSeconds(1)
                .setImageFile("image2.png").setImageWidthPx(1920).setImageHeightPx(957)
                .addNodes(RoadNode.newBuilder().setNodeId("dumpNode"))
                .addNodes(RoadNode.newBuilder().setNodeId("ShovelOreHigh1"))
                .addNodes(RoadNode.newBuilder().setNodeId("ShovelOreHigh2"))
                .build().toByteArray(), 1, true);
        ingestion.handle("mine/sim/SIM_AUTH/state/vehicle/0", vehicle(0, 2, "ShovelOreHigh1").toByteArray(), 0, false);
        ingestion.handle("mine/sim/SIM_AUTH/state/vehicle/1", vehicle(1, 3, "ShovelOreHigh2").toByteArray(), 0, false);
        ingestion.handle("mine/sim/SIM_AUTH/state/shovel/0", shovel(0, 4).toByteArray(), 0, false);
        ingestion.handle("mine/sim/SIM_AUTH/state/shovel/1", shovel(1, 5).toByteArray(), 0, false);
    }

    @Test void rolesIsolationAuditTerminalActionsAndAlertLifecycle() throws Exception {
        assertThat(get("/api/v1/simulations/SIM_AUTH/snapshot", null).statusCode()).isEqualTo(401);
        String truck = login("truck0", "truck-pass");
        JsonNode truckSnapshot = body(get("/api/v1/simulations/SIM_AUTH/snapshot", truck));
        assertThat(truckSnapshot.path("vehicles")).hasSize(1);
        assertThat(truckSnapshot.at("/vehicles/0/vehicleId").asInt()).isZero();
        assertThat(truckSnapshot.path("shovels")).isEmpty();
        assertThat(get("/api/v1/simulations/SIM_AUTH/vehicles/1/decisions", truck).statusCode()).isEqualTo(403);
        assertThat(post("/api/v1/simulations/SIM_AUTH/manual-dispatch", truck,
                "{\"vehicleId\":0,\"targetNode\":\"ShovelOreHigh2\"}").statusCode()).isEqualTo(403);

        String commandId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        traces.create("SIM_AUTH", "TASK", "DRL_DISPATCH", 0, 0, "UNLOAD_COMPLETE",
                Map.of("requestId", "auth-test"), Map.of("targetNode", "ShovelOreHigh1"),
                null, "test decision", commandId, "test/topic");
        assertThat(post("/api/v1/simulations/SIM_AUTH/terminal-actions", truck,
                "{\"actionType\":\"REQUEST\",\"detail\":\"申请检修\"}").statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/simulations/SIM_AUTH/terminal-actions", truck,
                "{\"actionType\":\"TASK_CONFIRMATION\",\"commandId\":\"" + commandId + "\"}").statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/simulations/SIM_AUTH/terminal-actions", truck,
                "{\"actionType\":\"EXCEPTION\",\"detail\":\"制动温度高\",\"severity\":\"CRITICAL\",\"code\":\"BRAKE_TEMP\"}").statusCode()).isEqualTo(200);

        String shovel = login("shovel0", "shovel-pass");
        JsonNode shovelSnapshot = body(get("/api/v1/simulations/SIM_AUTH/snapshot", shovel));
        assertThat(shovelSnapshot.path("shovels")).hasSize(1);
        assertThat(shovelSnapshot.at("/shovels/0/shovelId").asInt()).isZero();
        assertThat(shovelSnapshot.path("vehicles")).hasSize(1);
        assertThat(get("/api/v1/simulations/SIM_AUTH/shovels/1/decisions", shovel).statusCode()).isEqualTo(403);
        assertThat(post("/api/v1/simulations/SIM_AUTH/terminal-actions", shovel,
                "{\"actionType\":\"TASK_CONFIRMATION\",\"commandId\":\"" + commandId + "\"}").statusCode()).isEqualTo(200);

        String dispatcher = login("dispatcher", "dispatch-pass");
        JsonNode alerts = body(get("/api/v1/simulations/SIM_AUTH/alerts", dispatcher));
        assertThat(alerts).hasSize(1);
        String alertId = alerts.get(0).path("alertId").asText();
        assertThat(post("/api/v1/simulations/SIM_AUTH/alerts/" + alertId + "/process", dispatcher, "{\"detail\":\"\"}").statusCode()).isEqualTo(409);
        assertThat(post("/api/v1/simulations/SIM_AUTH/alerts/" + alertId + "/acknowledge", dispatcher, "{\"detail\":\"\"}").statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/simulations/SIM_AUTH/alerts/" + alertId + "/process", dispatcher, "{\"detail\":\"\"}").statusCode()).isEqualTo(200);
        JsonNode closed = body(post("/api/v1/simulations/SIM_AUTH/alerts/" + alertId + "/close", dispatcher, "{\"detail\":\"现场确认恢复\"}"));
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("closedBy").asText()).isEqualTo("dispatcher");
        assertThat(closed.path("resolution").asText()).isEqualTo("现场确认恢复");

        JsonNode audit = body(get("/api/v1/simulations/SIM_AUTH/audit?limit=100", dispatcher));
        assertThat(audit).anySatisfy(item -> {
            assertThat(item.path("actorUsername").asText()).isEqualTo("truck0");
            assertThat(item.path("operation").asText()).isEqualTo("MANUAL_DISPATCH");
            assertThat(item.path("objectId").asText()).isEqualTo("0");
            assertThat(item.path("result").asText()).isEqualTo("DENIED");
            assertThat(item.path("occurredAtMs").asLong()).isPositive();
        });
        assertThat(audit).anySatisfy(item -> {
            assertThat(item.path("actorUsername").asText()).isEqualTo("dispatcher");
            assertThat(item.path("operation").asText()).isEqualTo("ALERT_CLOSE");
            assertThat(item.path("objectId").asText()).isEqualTo(alertId);
            assertThat(item.path("result").asText()).isEqualTo("SUCCESS");
        });
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login", null,
                json.writeValueAsString(Map.of("username", username, "password", password)));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }
    private HttpResponse<String> get(String path,String cookie)throws Exception{return send("GET",path,cookie,null);}
    private HttpResponse<String> post(String path,String cookie,String value)throws Exception{return send("POST",path,cookie,value);}
    private HttpResponse<String> send(String method,String path,String cookie,String value)throws Exception{HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(cookie!=null)b.header("Cookie",cookie);b.method(method,value==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(value));return client.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    private JsonNode body(HttpResponse<String> response)throws Exception{assertThat(response.statusCode()).isBetween(200,299);return json.readTree(response.body());}
    private static VehicleState vehicle(int id,long seq,String target){return VehicleState.newBuilder().setSimulationId("SIM_AUTH").setSequence(seq).setSimTimeSeconds(2).setSentWallTimeMs(System.currentTimeMillis()).setVehicleId(id).setDestinationNode(target).setCurrentNode("dumpNode").setCurrentSegmentId("segment").setStatechartState("MoveSegment").build();}
    private static ShovelState shovel(int id,long seq){return ShovelState.newBuilder().setSimulationId("SIM_AUTH").setSequence(seq).setSimTimeSeconds(2).setShovelId(id).setShovelName("S"+id).setAvailable(true).build();}
    private static Path database(){try{return Files.createTempDirectory("dispatch-wp3-").resolve("state.db");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
