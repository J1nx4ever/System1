package com.mine.dispatch;

import com.mine.dispatch.capability.tracking.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CycleStoryIntegrationTest {
    static final Path DB=database();
    static Path database(){try{return Files.createTempDirectory("cycle-story-test-").resolve("test.db");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){
        r.add("dispatch.storage.jdbc-url",()->"jdbc:sqlite:"+DB);r.add("dispatch.mqtt.enabled",()->false);
        r.add("dispatch.inference.enabled",()->false);r.add("dispatch.security.bootstrap-users",()->"test|test-pass|DISPATCHER||");
    }
    @Autowired RunTrackingRepository runs;
    @Autowired ProductionEventService events;
    @Autowired CycleStoryRepository stories;
    void decision(String command,int vehicle,long time,String source)throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+DB);PreparedStatement s=c.prepareStatement(
            "INSERT INTO decision_trace VALUES(?,'SIM_STORY','TASK',?,?,0,'TEST','{}','{}',NULL,'PASSED','test',?,'test/topic',?,?,NULL,NULL,'EXECUTED','test')")) {
            s.setString(1,"decision-"+command);s.setString(2,source);s.setInt(3,vehicle);s.setString(4,command);s.setLong(5,time);s.setLong(6,time);s.executeUpdate();
        }
    }
    ProductionEventService.Event event(String run,String cycle,String command,String previous) {
        return new ProductionEventService.Event(1,cycle+"-arrive",run,"SIM_STORY",cycle,0,"ARRIVE_SHOVEL",0,1,System.currentTimeMillis(),command,null,null,null,null,previous);
    }
    @Test void explicitManualLineageAndUnknownOldEventsNeverUseNearestDecision()throws Exception {
        var run=runs.start("SIM_STORY",null,null,Map.of(),"test");String id=(String)run.get("run_id");long start=((Number)run.get("started_at_ms")).longValue();
        decision("auto",0,start,"DRL_DISPATCH");decision("manual",0,start,"MANUAL_DISPATCH");
        decision("wrong-vehicle",1,start,"DRL_DISPATCH");decision("old-run",0,start-1,"DRL_DISPATCH");
        events.ingest(event(id,"manual-cycle","manual","auto"));
        var story=stories.story(id,"manual-cycle");
        assertThat(story.get("association")).isEqualTo("EXPLICIT_COMMAND_REFERENCES");
        assertThat((List<?>)story.get("taskDecisions")).hasSize(2);
        assertThat(story.get("taskId")).isNull();
        events.ingest(event(id,"legacy-cycle",null,null));
        assertThat(stories.story(id,"legacy-cycle").get("association")).isEqualTo("UNKNOWN");
        assertThat((List<?>)stories.story(id,"legacy-cycle").get("taskDecisions")).isEmpty();
        assertThatThrownBy(()->events.ingest(event(id,"wrong-cycle","wrong-vehicle",null))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->events.ingest(event(id,"old-cycle","old-run",null))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->stories.story(id,"missing-cycle")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->stories.story("missing-run","manual-cycle")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void snapshotPreservesInputAndLabelsMissingEvidence() {
        var obs=DRL.grpc.Observation.newBuilder().setSchemaId("dispatch.v1-27");for(int i=0;i<27;i++)obs.addValues(i/27f);
        var request=DRL.grpc.DispatchRequest.newBuilder().setObs(obs).setSimTimeSeconds(42).build();
        var snapshot=DispatchEvidence.capture(request,null,List.of(),null,null);
        assertThat((List<?>)snapshot.get("normalizedObservation")).hasSize(27);
        assertThat((List<?>)snapshot.get("featureNames")).hasSize(27);
        assertThat((List<?>)snapshot.get("candidateStatesAtGateway")).hasSize(4);
        assertThat(snapshot.get("modelVersion")).isNull();assertThat(snapshot.get("rawProductionState")).isNull();
        assertThat(snapshot.get("causalExplanation")).isNull();
        assertThat(DispatchEvidence.capture(request,null,List.of(),null,"actual-version").get("modelVersionSource")).isEqualTo("OPERATOR_ENV_DECLARATION");
    }
}
