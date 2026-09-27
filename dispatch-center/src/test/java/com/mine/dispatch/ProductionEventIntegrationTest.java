package com.mine.dispatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.tracking.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ProductionEventIntegrationTest {
    static final Path DB=database();
    static Path database(){try{return Files.createTempDirectory("production-event-test-").resolve("test.db");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){
        r.add("dispatch.storage.jdbc-url",()->"jdbc:sqlite:"+DB);
        r.add("dispatch.mqtt.enabled",()->false);r.add("dispatch.inference.enabled",()->false);
        r.add("dispatch.security.bootstrap-users",()->"test|test-pass|DISPATCHER||");
    }
    @Autowired RunTrackingRepository runs;
    @Autowired ProductionEventService events;
    @Autowired ObjectMapper json;
    @Autowired com.mine.dispatch.capability.vehicledata.MqttIngestionClient mqtt;
    static final List<String> TYPES=List.of("ARRIVE_SHOVEL","QUEUE_LOAD","LOADING_START","LOADING_FINISH","ARRIVE_DUMP","QUEUE_DUMP","DUMP_START","DUMP_FINISH");
    ProductionEventService.Event event(String run,String cycle,int n,double time){
        return new ProductionEventService.Event(1,cycle+"-"+n,run,"SIM_PRODUCTION",cycle,0,TYPES.get(n),n<4?0:1,time,System.currentTimeMillis(),null,n==7?100.0:null,n==7?0.2:null,n==7?true:null,n==7?true:null);
    }
    @Test void existingMqttCallbackRoutesProductionAndPersistsRejections() throws Exception {
        String run=(String)runs.start("SIM_CALLBACK",null,null,Map.of(),"test").get("run_id");
        var e=new ProductionEventService.Event(1,"callback-event",run,"SIM_CALLBACK","callback-cycle",0,"ARRIVE_SHOVEL",0,1,System.currentTimeMillis(),null,null,null,null,null);
        var message=new org.eclipse.paho.client.mqttv3.MqttMessage(json.writeValueAsBytes(e));
        mqtt.messageArrived("mine/sim/SIM_CALLBACK/event/production/0",message);
        mqtt.messageArrived("mine/sim/SIM_CALLBACK/event/production/0",message);
        assertThat(events.events(run,null,100)).hasSize(1);
        mqtt.messageArrived("mine/sim/SIM_CALLBACK/event/production/1",message);
        assertThat(events.events(run,null,100)).hasSize(1);
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+DB);var q=c.createStatement();
            var r=q.executeQuery("SELECT count(*) FROM message_rejections WHERE topic='mine/sim/SIM_CALLBACK/event/production/1'")) {
            assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(1);
        }
        runs.finish(run,null);
    }
    @Test void actualEventsOnlyDeduplicatedAndIncompleteCyclesRemainUnknown() throws Exception {
        String run=(String)runs.start("SIM_PRODUCTION",null,0.0,Map.of("source","TEST_FIXTURE"),"test").get("run_id");
        assertThat(events.summary(run).get("oreTons")).isNull();
        var first=event(run,"cycle1",0,0);
        assertThat(events.handle("mine/sim/SIM_PRODUCTION/event/production/0",json.writeValueAsBytes(first),false).result()).isEqualTo("ACCEPTED");
        assertThat(events.handle("mine/sim/SIM_PRODUCTION/event/production/0",json.writeValueAsBytes(first),false).result()).isEqualTo("DUPLICATE");
        assertThat(events.events(run,null,100)).hasSize(1);
        assertThat(events.summary(run).get("incompleteCycles")).isEqualTo(1);
        assertThat(events.summary(run).get("oreTons")).isNull();
        assertThatThrownBy(()->events.ingest(event(run,"cycle1",0,99))).isInstanceOf(ResponseStatusException.class);
        // Out-of-order delivery is allowed; production chronology still comes from simulation time.
        for(int n=7;n>0;n--)events.ingest(event(run,"cycle1",n,n*10));
        assertThat(events.events(run,null,3,true)).extracting(ProductionEventService.Event::simTimeSeconds)
            .containsExactly(50.0,60.0,70.0);
        assertThat(runs.events(run,0,"",0,null,100)).hasSize(8);
        assertThat(runs.events(run,0,"",0,null,100)).allSatisfy(e->assertThat(e.get("related_cycle_id")).isEqualTo("cycle1"));
        assertThat(runs.evidence(run,"production_event:cycle1-7")).containsKey("sourceRecord");
        var summary=events.summary(run);
        assertThat(summary.get("completeCycles")).isEqualTo(1);
        assertThat(summary.get("oreTons")).isEqualTo(100.0);
        assertThat(summary.get("weightedOreGrade")).isEqualTo(0.2);
        assertThat(summary.get("averageLoadWaitSeconds")).isEqualTo(10.0);
        assertThat(summary.get("averageArrivalToDumpFinishSeconds")).isEqualTo(70.0);
        assertThatThrownBy(()->events.handle("mine/sim/SIM_PRODUCTION/event/production/1",json.writeValueAsBytes(first),false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->events.handle("mine/sim/SIM_PRODUCTION/event/production/0",json.writeValueAsBytes(first),true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->events.ingest(event("missing-run","cycle2",0,0))).isInstanceOf(ResponseStatusException.class);
        for(int n=0;n<8;n++)events.ingest(event(run,"invalid-cycle",n,n==7?0:n*10));
        assertThat(events.summary(run).get("invalidOrderCycles")).isEqualTo(1);
        assertThat(events.summary(run).get("oreTons")).isEqualTo(100.0);
        events.migrate();
        assertThat(events.events(run,null,100)).hasSize(16);
        runs.finish(run,null);
    }
}


