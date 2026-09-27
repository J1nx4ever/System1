package com.mine.dispatch.capability.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.production.DispatchProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Observational ingestion only: this service never publishes control commands. */
@Service
@DependsOn("runTrackingRepository")
public class ProductionEventService {
    public record Event(int schemaVersion,String eventId,String runId,String simulationId,
                        String cycleId,int vehicleId,String eventType,int siteId,
                        double simTimeSeconds,long sentWallTimeMs,String commandId,
                        Double payloadTons,Double payloadGrade,Boolean producesOre,Boolean productionEligible,String previousCommandId) {
        public Event(int schemaVersion,String eventId,String runId,String simulationId,String cycleId,int vehicleId,
                String eventType,int siteId,double simTimeSeconds,long sentWallTimeMs,String commandId,
                Double payloadTons,Double payloadGrade,Boolean producesOre,Boolean productionEligible) {
            this(schemaVersion,eventId,runId,simulationId,cycleId,vehicleId,eventType,siteId,simTimeSeconds,
                 sentWallTimeMs,commandId,payloadTons,payloadGrade,producesOre,productionEligible,null);
        }
    }
    public record Receipt(String eventId,String result) {}
    private static final List<String> ORDER=List.of("ARRIVE_SHOVEL","QUEUE_LOAD","LOADING_START",
            "LOADING_FINISH","ARRIVE_DUMP","QUEUE_DUMP","DUMP_START","DUMP_FINISH");
    private static final Pattern TOPIC=Pattern.compile("^mine/sim/([A-Za-z0-9_-]{1,64})/event/production/([0-9]+)$");
    private final String url;
    private final ObjectMapper json;
    public ProductionEventService(DispatchProperties p,ObjectMapper j) { url=p.getStorage().getJdbcUrl();json=j; }
    private Connection open() throws SQLException {
        Connection c=DriverManager.getConnection(url);
        try(Statement s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA foreign_keys=ON");}
        return c;
    }
    @PostConstruct public void migrate() throws Exception {
        String sql;
        try(var in=new ClassPathResource("db/migration/V6__production_events.sql").getInputStream()) {
            sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                try(Statement s=c.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);}
                try(PreparedStatement s=c.prepareStatement("INSERT OR IGNORE INTO schema_migrations VALUES('6','production_events',?)")) {
                    s.setLong(1,System.currentTimeMillis());s.executeUpdate();
                }
                c.commit();
            } catch(Exception e){c.rollback();throw e;}
        }
    }
    public Receipt handle(String topic,byte[] bytes,boolean retained) throws Exception {
        var m=TOPIC.matcher(topic);
        if(!m.matches()||retained||bytes.length>16384)bad("invalid production topic, retained flag or payload size");
        Event e=json.readValue(bytes,Event.class);
        if(!m.group(1).equals(e.simulationId())||!m.group(2).equals(Integer.toString(e.vehicleId())))bad("topic/payload identity mismatch");
        return ingest(e);
    }
    public Receipt ingest(Event e) throws Exception {
        if(e.schemaVersion()!=1||!ORDER.contains(e.eventType())||e.vehicleId()<0||e.siteId()<0
                ||!Double.isFinite(e.simTimeSeconds())||e.simTimeSeconds()<0||e.sentWallTimeMs()<=0)bad("invalid event fields");
        for(String id:List.of(Objects.toString(e.eventId(),""),Objects.toString(e.runId(),""),Objects.toString(e.cycleId(),""))) {
            if(!id.matches("[A-Za-z0-9_-]{1,80}"))bad("invalid event/run/cycle identity");
        }
        if(e.payloadTons()!=null&&(!Double.isFinite(e.payloadTons())||e.payloadTons()<0))bad("invalid tons");
        if(e.payloadGrade()!=null&&(!Double.isFinite(e.payloadGrade())||e.payloadGrade()<0||e.payloadGrade()>1))bad("invalid grade fraction");
        if("DUMP_FINISH".equals(e.eventType())&&(e.payloadTons()==null||e.payloadGrade()==null||e.producesOre()==null||e.productionEligible()==null))bad("dump completion requires original payload and eligibility");
        String payload=json.writeValueAsString(e);
        try(Connection c=open()) {
            // Serialize duplicate/cycle ownership checks across concurrent MQTT deliveries.
            try(Statement s=c.createStatement()){s.execute("BEGIN IMMEDIATE");}
            try {
                try(PreparedStatement s=c.prepareStatement("SELECT simulation_id,started_at_ms,ended_at_ms FROM run_context WHERE run_id=?")) {
                    s.setString(1,e.runId());try(ResultSet r=s.executeQuery()) {
                        if(!r.next()||!Objects.equals(r.getString(1),e.simulationId()))bad("run/instance mismatch");
                        if(e.sentWallTimeMs()<r.getLong(2)||(r.getObject(3)!=null&&e.sentWallTimeMs()>=r.getLong(3)))bad("source time outside run");
                    }
                }
                try(PreparedStatement s=c.prepareStatement("SELECT payload_json FROM production_event WHERE event_id=? OR (run_id=? AND cycle_id=? AND event_type=?)")) {
                    s.setString(1,e.eventId());s.setString(2,e.runId());s.setString(3,e.cycleId());s.setString(4,e.eventType());
                    try(ResultSet r=s.executeQuery()) {
                        if(r.next()) {
                            if(!e.equals(json.readValue(r.getString(1),Event.class)))conflict("duplicate identity with different evidence");
                            try(Statement done=c.createStatement()){done.execute("COMMIT");}
                            return new Receipt(e.eventId(),"DUPLICATE");
                        }
                    }
                }
                try(PreparedStatement s=c.prepareStatement("SELECT vehicle_id,site_id,event_type FROM production_event WHERE run_id=? AND cycle_id=?")) {
                    s.setString(1,e.runId());s.setString(2,e.cycleId());try(ResultSet r=s.executeQuery()) {
                        while(r.next()) {
                            if(r.getInt(1)!=e.vehicleId())bad("cycle belongs to another vehicle");
                            if(ORDER.indexOf(r.getString(3))/4==ORDER.indexOf(e.eventType())/4&&r.getInt(2)!=e.siteId())bad("site changed within service phase");
                        }
                    }
                }
                for(String command:Arrays.asList(e.commandId(),e.previousCommandId())) {
                    if(command==null)continue;
                    try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM decision_trace d JOIN run_context r ON r.run_id=? AND r.simulation_id=d.simulation_id WHERE d.command_id=? AND d.vehicle_id=? AND d.decision_type='TASK' AND d.created_at_ms>=r.started_at_ms AND (r.ended_at_ms IS NULL OR d.created_at_ms<r.ended_at_ms)")) {
                        s.setString(1,e.runId());s.setString(2,command);s.setInt(3,e.vehicleId());
                        try(ResultSet r=s.executeQuery()){if(!r.next())bad("task command does not belong to vehicle/run");}
                    }
                }
                try(PreparedStatement s=c.prepareStatement("INSERT INTO production_event VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    int i=1;s.setString(i++,e.eventId());s.setString(i++,e.runId());s.setString(i++,e.simulationId());s.setString(i++,e.cycleId());
                    s.setInt(i++,e.vehicleId());s.setString(i++,e.eventType());s.setInt(i++,e.siteId());s.setDouble(i++,e.simTimeSeconds());
                    s.setLong(i++,e.sentWallTimeMs());s.setLong(i++,System.currentTimeMillis());s.setString(i,payload);s.executeUpdate();
                }
                try(Statement s=c.createStatement()){s.execute("COMMIT");}
                return new Receipt(e.eventId(),"ACCEPTED");
            } catch(Exception ex){try(Statement s=c.createStatement()){s.execute("ROLLBACK");}throw ex;}
        }
    }
    private void exists(String run) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT 1 FROM run_context WHERE run_id=?")) {
            s.setString(1,run);try(ResultSet r=s.executeQuery()){if(!r.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"run not found");}
        }
    }
    public List<Event> events(String run,Integer vehicle,int limit) throws Exception {
        exists(run);if(limit<1||limit>1000)bad("limit must be 1..1000");
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT payload_json FROM production_event WHERE run_id=?"+(vehicle==null?"":" AND vehicle_id=?")+" ORDER BY sim_time_seconds,event_id LIMIT ?")) {
            s.setString(1,run);int i=2;if(vehicle!=null)s.setInt(i++,vehicle);s.setInt(i,limit);
            List<Event> out=new ArrayList<>();try(ResultSet r=s.executeQuery()){while(r.next())out.add(json.readValue(r.getString(1),Event.class));}return out;
        }
    }
    public Map<String,Object> summary(String run) throws Exception {
        exists(run);
        Map<String,Map<String,Event>> cycles=new LinkedHashMap<>();
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT payload_json FROM production_event WHERE run_id=?")) {
            s.setString(1,run);try(ResultSet r=s.executeQuery()){while(r.next()){Event e=json.readValue(r.getString(1),Event.class);cycles.computeIfAbsent(e.cycleId(),k->new HashMap<>()).put(e.eventType(),e);}}
        }
        int complete=0,invalid=0,eligible=0;double ore=0,waste=0,metal=0,loadWait=0,dumpWait=0,loading=0,dumping=0,cycleDuration=0;
        for(var cycle:cycles.values()) {
            if(!cycle.keySet().containsAll(ORDER))continue;
            boolean ordered=true;double previous=-1;
            for(String type:ORDER){double t=cycle.get(type).simTimeSeconds();if(t<previous)ordered=false;previous=t;}
            if(!ordered){invalid++;continue;}complete++;
            Event finish=cycle.get("DUMP_FINISH");
            if(!Boolean.TRUE.equals(finish.productionEligible()))continue;eligible++;
            if(Boolean.TRUE.equals(finish.producesOre())){ore+=finish.payloadTons();metal+=finish.payloadTons()*finish.payloadGrade();}else waste+=finish.payloadTons();
            loadWait+=duration(cycle,"QUEUE_LOAD","LOADING_START");dumpWait+=duration(cycle,"QUEUE_DUMP","DUMP_START");
            loading+=duration(cycle,"LOADING_START","LOADING_FINISH");dumping+=duration(cycle,"DUMP_START","DUMP_FINISH");
            cycleDuration+=duration(cycle,"ARRIVE_SHOVEL","DUMP_FINISH");
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("runId",run);out.put("observedCycles",cycles.size());out.put("completeCycles",complete);
        out.put("incompleteCycles",cycles.size()-complete-invalid);out.put("invalidOrderCycles",invalid);out.put("eligibleCompleteCycles",eligible);
        out.put("oreTons",eligible==0?null:ore);out.put("wasteTons",eligible==0?null:waste);out.put("weightedOreGrade",ore==0?null:metal/ore);
        out.put("averageLoadWaitSeconds",eligible==0?null:loadWait/eligible);out.put("averageDumpWaitSeconds",eligible==0?null:dumpWait/eligible);
        out.put("averageLoadingSeconds",eligible==0?null:loading/eligible);out.put("averageDumpingSeconds",eligible==0?null:dumping/eligible);
        out.put("averageArrivalToDumpFinishSeconds",eligible==0?null:cycleDuration/eligible);
        out.put("scope","Only eight-event complete, time-ordered and source-eligible cycles. Missing events excluded; not full dispatch-to-dispatch cycle or equipment utilization.");
        return out;
    }
    private double duration(Map<String,Event> c,String a,String b){return c.get(b).simTimeSeconds()-c.get(a).simTimeSeconds();}
    private void bad(String m){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private void conflict(String m){throw new ResponseStatusException(HttpStatus.CONFLICT,m);}
}


