package com.mine.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.access.TerminalConversationMqttClient;
import com.mine.dispatch.capability.access.TerminalConversationService;
import com.mine.dispatch.model.TerminalMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalConversationIntegrationTest {
    private static final Path DATABASE = database();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("dispatch.mqtt.enabled", () -> "false");
        registry.add("dispatch.inference.enabled", () -> "false");
        registry.add("dispatch.storage.jdbc-url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("dispatch.security.bootstrap-users", () ->
                "dispatcher|dispatch-pass|DISPATCHER||;truck0|truck-pass|VEHICLE|SIM_CHAT|0;" +
                "truck1|truck-pass|VEHICLE|SIM_CHAT|1;shovel0|shovel-pass|SHOVEL|SIM_CHAT|0");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired TerminalConversationService conversation;
    @MockBean TerminalConversationMqttClient mqtt;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void dispatcherPublishTerminalIsolationAndMqttResponse() throws Exception {
        String dispatcher = login("dispatcher", "dispatch-pass");
        String truck0 = login("truck0", "truck-pass");
        String truck1 = login("truck1", "truck-pass");
        String shovel0 = login("shovel0", "shovel-pass");
        String path = "/api/v1/simulations/SIM_CHAT/terminal-messages";

        assertThat(post(path, truck0, "{\"objectType\":\"VEHICLE\",\"objectId\":0,\"body\":\"not allowed\"}").statusCode()).isEqualTo(403);
        JsonNode outbound = body(post(path, dispatcher, "{\"objectType\":\"VEHICLE\",\"objectId\":0,\"body\":\"检查制动温度\"}"));
        String outboundId = outbound.path("messageId").asText();
        assertThat(outbound.path("status").asText()).isEqualTo("SENT");
        verify(mqtt).publish(any(TerminalMessage.class));

        assertThat(body(get(path, truck0))).hasSize(1);
        assertThat(body(get(path, truck1))).isEmpty();
        assertThat(body(get(path, shovel0))).isEmpty();
        assertThat(post(path + "/" + outboundId + "/reply", truck1, "{\"body\":\"伪造回复\"}").statusCode()).isEqualTo(403);

        JsonNode published = body(post(path + "/" + outboundId + "/reply", truck0, "{\"body\":\"已检查\"}"));
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body(get(path, dispatcher))).hasSize(1); // 仅发布，还未收到 MQTT 回流

        String responseId = published.path("messageId").asText();
        JsonNode mqttPayload = json.valueToTree(Map.of(
                "schemaVersion", 1, "messageId", responseId, "simulationId", "SIM_CHAT",
                "terminalType", "VEHICLE", "terminalId", 0, "senderName", "truck0",
                "text", "已检查", "sentAtMs", System.currentTimeMillis(),
                "replyToMessageId", outboundId));
        conversation.receive("mine/sim/SIM_CHAT/terminal/VEHICLE/0/response", mqttPayload);
        conversation.receive("mine/sim/SIM_CHAT/terminal/VEHICLE/0/response", mqttPayload); // QoS 1 重投不重复入库
        JsonNode all = body(get(path, dispatcher));
        assertThat(all).hasSize(2);
        assertThat(all.get(0).path("status").asText()).isEqualTo("DELIVERED");
        assertThat(all.get(1).path("body").asText()).isEqualTo("已检查");
        assertThat(body(get(path, truck0))).hasSize(2);
        assertThat(body(get(path, truck1))).isEmpty();
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login", null,
                json.writeValueAsString(Map.of("username", username, "password", password)));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }
    private HttpResponse<String> get(String path,String cookie)throws Exception{return send("GET",path,cookie,null);}
    private HttpResponse<String> post(String path,String cookie,String value)throws Exception{return send("POST",path,cookie,value);}
    private HttpResponse<String> send(String method,String path,String cookie,String value)throws Exception{
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(cookie!=null)builder.header("Cookie",cookie);
        builder.method(method,value==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(value));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response)throws Exception{
        assertThat(response.statusCode()).isBetween(200,299);
        return json.readTree(response.body());
    }
    private static Path database(){
        try{return Files.createTempDirectory("dispatch-chat-").resolve("state.db");}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
}
