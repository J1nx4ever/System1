package com.mine.dispatch.capability.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.production.DispatchProperties;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.util.*;

@Repository
public class CycleStoryRepository {
    private final String url;
    private final ObjectMapper json;
    private final RunTrackingRepository runs;
    public CycleStoryRepository(DispatchProperties p,ObjectMapper j,RunTrackingRepository r){url=p.getStorage().getJdbcUrl();json=j;runs=r;}
    public Map<String,Object> story(String runId,String cycleId) throws Exception {
        runs.run(runId);
        List<ProductionEventService.Event> events=new ArrayList<>();
        try(Connection c=DriverManager.getConnection(url);PreparedStatement s=c.prepareStatement(
                "SELECT payload_json FROM production_event WHERE run_id=? AND cycle_id=? ORDER BY sim_time_seconds,event_id")) {
            s.setString(1,runId);s.setString(2,cycleId);
            try(ResultSet r=s.executeQuery()){while(r.next())events.add(json.readValue(r.getString(1),ProductionEventService.Event.class));}
        }
        if(events.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"cycle not found in run");
        Set<String> previousIds=new LinkedHashSet<>();
        for(var e:events)if(e.previousCycleId()!=null)previousIds.add(e.previousCycleId());
        ProductionEventService.Event previousUnload=null;
        String previousStatus=previousIds.isEmpty()?"NO_SOURCE_REFERENCE":"UNRESOLVED";
        if(previousIds.size()>1)previousStatus="CONFLICTING_REFERENCES";
        if(previousIds.size()==1) {
            try(Connection c=DriverManager.getConnection(url);PreparedStatement s=c.prepareStatement(
                    "SELECT payload_json FROM production_event WHERE run_id=? AND cycle_id=? AND vehicle_id=? AND event_type='DUMP_FINISH'")) {
                s.setString(1,runId);s.setString(2,previousIds.iterator().next());s.setInt(3,events.get(0).vehicleId());
                try(ResultSet r=s.executeQuery()){if(r.next()) {
                    var candidate=json.readValue(r.getString(1),ProductionEventService.Event.class);
                    if(candidate.simTimeSeconds()<=events.get(0).simTimeSeconds()) {previousUnload=candidate;previousStatus="EXPLICIT_PREVIOUS_CYCLE";}
                    else previousStatus="INVALID_ORDER";
                }}
            }
        }
        Map<String,ProductionEventService.Event> stages=new HashMap<>();for(var e:events)stages.put(e.eventType(),e);
        List<String> stageOrder=List.of("ARRIVE_SHOVEL","QUEUE_LOAD","LOADING_START","LOADING_FINISH","ARRIVE_DUMP","QUEUE_DUMP","DUMP_START","DUMP_FINISH");
        List<String> transportOrder=List.of("HAUL_EMPTY_START","ARRIVE_SHOVEL","QUEUE_LOAD","LOADING_START","LOADING_FINISH","HAUL_FULL_START","ARRIVE_DUMP","QUEUE_DUMP","DUMP_START","DUMP_FINISH");
        boolean stageComplete=ordered(stages,stageOrder),transportComplete=ordered(stages,transportOrder);
        Set<String> ids=new LinkedHashSet<>();
        for(var e:events){if(e.previousCommandId()!=null)ids.add(e.previousCommandId());if(e.commandId()!=null)ids.add(e.commandId());}
        List<Map<String,Object>> decisions=new ArrayList<>();
        for(String id:ids) {
            try(Connection c=DriverManager.getConnection(url);PreparedStatement s=c.prepareStatement(
                    "SELECT d.* FROM decision_trace d JOIN run_context r ON r.run_id=? AND r.simulation_id=d.simulation_id WHERE d.command_id=? AND d.vehicle_id=? AND d.decision_type='TASK' AND d.created_at_ms>=r.started_at_ms AND (r.ended_at_ms IS NULL OR d.created_at_ms<r.ended_at_ms)")) {
                s.setString(1,runId);s.setString(2,id);s.setInt(3,events.get(0).vehicleId());
                try(ResultSet r=s.executeQuery()){if(r.next()) {
                    Map<String,Object> d=new LinkedHashMap<>();
                    d.put("decisionId",r.getString("decision_id"));d.put("commandId",id);d.put("source",r.getString("decision_source"));
                    d.put("createdAtMs",r.getLong("created_at_ms"));d.put("publishedAtMs",r.getObject("published_at_ms"));d.put("ackAtMs",r.getObject("ack_at_ms"));d.put("ackStatus",r.getString("ack_status"));d.put("ackDetail",r.getString("ack_detail"));
                    d.put("input",json.readTree(r.getString("input_summary_json")));d.put("output",json.readTree(r.getString("model_output_json")));
                    d.put("validationStatus",r.getString("validation_status"));d.put("validationDetail",r.getString("validation_detail"));
                    d.put("evidenceReference","decision_trace:"+r.getString("decision_id"));decisions.add(d);
                }}
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("runId",runId);out.put("cycleId",cycleId);out.put("vehicleId",events.get(0).vehicleId());
        out.put("events",events);out.put("taskDecisions",decisions);
        out.put("previousUnload",previousUnload);out.put("previousCycleStatus",previousStatus);
        out.put("stageComplete",stageComplete);out.put("transportComplete",transportComplete);
        out.put("fullCycleComplete",transportComplete&&previousUnload!=null&&!decisions.isEmpty()&&decisions.size()==ids.size()&&decisions.stream().allMatch(d->"EXECUTED".equals(d.get("ackStatus"))));
        out.put("association",ids.isEmpty()?"UNKNOWN":decisions.size()==ids.size()?"EXPLICIT_COMMAND_REFERENCES":"PARTIALLY_RESOLVED");
        out.put("taskId",null);out.put("scope","Only source-reported command references, never nearest-time matching. Previous/current references preserve applied manual override lineage. Speed controls are not assigned to a cycle without source correlation.");
        return out;
    }
    private static boolean ordered(Map<String,ProductionEventService.Event> events,List<String> order) {
        double last=-1;for(String type:order){var e=events.get(type);if(e==null||e.simTimeSeconds()<last)return false;last=e.simTimeSeconds();}return true;
    }
}
