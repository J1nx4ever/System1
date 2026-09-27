package com.mine.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.vehicledata.FieldMessageIngestionService;
import mine.field.v1.MapState;
import mine.field.v1.RoadNode;
import mine.field.v1.RoadSegment;
import mine.field.v1.VehicleState;
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
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManualDispatchTravelFallbackIntegrationTest {
    private static final Path DATABASE=database();
    static Path database(){try{return Files.createTempDirectory("dispatch-travel-test-").resolve("test.db");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("dispatch.storage.jdbc-url",()->"jdbc:sqlite:"+DATABASE);
        registry.add("dispatch.mqtt.enabled",()->false);
        registry.add("dispatch.inference.enabled",()->false);
        registry.add("dispatch.security.enabled",()->false);
    }
    @LocalServerPort int port;
    @Autowired FieldMessageIngestionService ingestion;
    @Autowired ObjectMapper json;

    @Test void genericStateAndMissingSegmentUseVehicleSpeedAsTravelEvidence()throws Exception {
        MapState map=MapState.newBuilder().setSimulationId("SIM_MOVE").setSequence(10).setSimTimeSeconds(40)
                .setImageFile("image2.png").setImageWidthPx(1920).setImageHeightPx(957)
                .addNodes(RoadNode.newBuilder().setNodeId("dumpNode").setX(0).setY(0))
                .addNodes(RoadNode.newBuilder().setNodeId("ShovelOreHigh1").setX(10).setY(0))
                .addNodes(RoadNode.newBuilder().setNodeId("ShovelOreHigh2").setX(20).setY(0))
                .addSegments(RoadSegment.newBuilder().setSegmentId("dumpNode_ShovelOreHigh2")
                        .setFromNodeId("dumpNode").setToNodeId("ShovelOreHigh2").setLengthModelUnits(20))
                .build();
        VehicleState vehicle=VehicleState.newBuilder().setSimulationId("SIM_MOVE").setSequence(11)
                .setSimTimeSeconds(40).setSentWallTimeMs(System.currentTimeMillis()).setVehicleId(7)
                .setX(5).setY(0).setSpeedMps(4).setLoaded(false).setCurrentNode("dumpNode")
                .setDestinationNode("ShovelOreHigh1").setStatechartState("state").build();
        ingestion.handle("mine/sim/SIM_MOVE/state/map",map.toByteArray(),1,true);
        ingestion.handle("mine/sim/SIM_MOVE/state/vehicle/7",vehicle.toByteArray(),0,false);

        HttpResponse<String> response=HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:"+port+"/api/v1/simulations/SIM_MOVE/manual-dispatch"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"vehicleId\":7,\"targetNode\":\"ShovelOreHigh2\"}"))
                .build(),HttpResponse.BodyHandlers.ofString());
        JsonNode body=json.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body.path("status").asText()).isEqualTo("PUBLISH_FAILED");
        assertThat(body.path("statusMessage").asText()).doesNotContain("不在行驶路段");
    }
}