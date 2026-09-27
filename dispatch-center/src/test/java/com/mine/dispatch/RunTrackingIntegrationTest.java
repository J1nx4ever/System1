package com.mine.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.tracking.RunTrackingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunTrackingIntegrationTest {
    static final Path DB = temporaryDatabase();
    static Path temporaryDatabase() {
        try { return Files.createTempDirectory("run-tracking-test-").resolve("test.db"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("dispatch.storage.jdbc-url",()->"jdbc:sqlite:"+DB);
        r.add("dispatch.mqtt.enabled",()->false);
        r.add("dispatch.inference.enabled",()->false);
        r.add("dispatch.security.bootstrap-users",()->"dispatcher|test-pass|DISPATCHER||;truck|test-pass|VEHICLE|SIM_RUN|0");
    }
    @Autowired RunTrackingRepository tracking;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newHttpClient();

    @Test void boundariesProjectionReplayAndRestartAreReadOnlyAndRepeatable() throws Exception {
        Map<String,Object> run=tracking.start("SIM_RUN","test-scenario",0.0,Map.of("dataSource","TEST_FIXTURE"),"test");
        String id=(String)run.get("run_id");
        long start=((Number)run.get("started_at_ms")).longValue();
        // Synthetic database evidence is strictly test-only, never demo data.
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+DB);Statement s=c.createStatement()) {
            s.executeUpdate("INSERT INTO vehicle_state_history(simulation_id,vehicle_id,sequence,sim_time_seconds,sent_wall_time_ms,received_at_ms,x,y,speed_mps,loaded,payload_tons,current_node,destination_node,current_segment_id,target_load_site_id,target_dump_site_id,statechart_state) VALUES('SIM_RUN',0,1,2,0,"+start+",1,2,3,0,0,'A','B','AB',0,0,'Moving')");
            s.executeUpdate("INSERT INTO decision_trace VALUES('d1','SIM_RUN','TASK','DRL_DISPATCH',0,0,'UNLOAD_COMPLETE','{\"simTimeSeconds\":2,\"values\":[1]}','{\"action\":0}',NULL,'PASSED','test','c1','test/topic',"+start+","+start+","+start+","+start+",'EXECUTED','test')");
        }
        Map<String,Object> finished=tracking.finish(id,10.0);
        long end=((Number)finished.get("ended_at_ms")).longValue();
        List<Map<String,Object>> events=tracking.events(id,0,"",null,null,100);
        assertThat(events).hasSize(4);
        assertThat(events).extracting(e->e.get("event_id")).doesNotHaveDuplicates();
        assertThat(tracking.events(id,0,"",null,null,100)).isEqualTo(events);
        Map<String,Object> first=events.get(0);
        assertThat(tracking.events(id,start,(String)first.get("event_id"),null,null,100)).hasSize(3);
        assertThat(tracking.events(id,0,"",1,null,100)).isEmpty();
        assertThat(tracking.events(id,0,"",0,"c1",100)).hasSize(3);
        assertThat(events).noneMatch(e->"TASK_FINISHED".equals(e.get("event_type")));
        assertThat(events).filteredOn(e->"COMMAND_ACK".equals(e.get("event_type")))
                .allSatisfy(e->assertThat(e.get("sim_time_seconds")).isNull());
        assertThat(tracking.summary(id).get("productionSummary")).isNull();
        assertThat((List<?>)tracking.replay(id,start).get("vehicle")).hasSize(1);
        assertThatThrownBy(()->tracking.replay(id,start-1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->tracking.evidence(id,"decision_trace:other:created")).isInstanceOf(ResponseStatusException.class);
        assertThat(tracking.evidence(id,"decision_trace:d1:created")).containsKey("sourceRecord");
        assertThat(tracking.finish(id,10.0).get("ended_at_ms")).isEqualTo(end);
        tracking.migrate(); // Reinitializing storage must not create another run or duplicate events.
        assertThat(tracking.list("SIM_RUN")).hasSize(1);
        assertThat(tracking.events(id,0,"",null,null,100)).isEqualTo(events);
        Map<String,Object> next=tracking.start("SIM_RUN",null,0.0,Map.of(),"test");
        assertThat(tracking.events((String)next.get("run_id"),0,"",null,null,100)).isEmpty();
        assertThatThrownBy(()->tracking.start("SIM_RUN",null,null,Map.of(),"test"))
                .isInstanceOf(ResponseStatusException.class);
        tracking.finish((String)next.get("run_id"),null);
    }

    @Test void endpointsRequireDispatcherAndDoNotExposeEvidenceToTerminals() throws Exception {
        assertThat(get("/api/v1/runs?simulationId=SIM_RUN",null).statusCode()).isEqualTo(401);
        String truck=login("truck");
        assertThat(get("/api/v1/runs?simulationId=SIM_RUN",truck).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/runs/arbitrary/replay?atWallTimeMs=1",truck).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/runs/arbitrary/evidence?eventId=d1",truck).statusCode()).isEqualTo(403);
        String dispatcher=login("dispatcher");
        HttpResponse<String> started=post("/api/v1/runs",dispatcher,"{\"simulationId\":\"SIM_HTTP\",\"metadata\":{}}");
        assertThat(started.statusCode()).isEqualTo(200);
        String id=json.readTree(started.body()).path("run_id").asText();
        assertThat(post("/api/v1/runs",dispatcher,"{\"simulationId\":\"SIM_HTTP\"}").statusCode()).isEqualTo(409);
        assertThat(get("/api/v1/runs/"+id+"/summary",dispatcher).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/runs/"+id+"/events?limit=0",dispatcher).statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/runs/"+id+"/finish",dispatcher,"{}").statusCode()).isEqualTo(200);
    }
    String login(String username) throws Exception {
        HttpResponse<String> r=post("/api/v1/auth/login",null,"{\"username\":\""+username+"\",\"password\":\"test-pass\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        return r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
    }
    HttpResponse<String> get(String path,String cookie) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(cookie!=null)b.header("Cookie",cookie);
        return client.send(b.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,String cookie,String body) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(cookie!=null)b.header("Cookie",cookie);
        return client.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
