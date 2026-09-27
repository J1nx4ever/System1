package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.AuditService;
import com.mine.dispatch.capability.access.AuthorizationService;
import com.mine.dispatch.capability.tracking.RunTrackingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/runs")
public class RunTrackingController {
    private final RunTrackingRepository tracking;
    private final AuthorizationService authorization;
    private final AuditService audit;
    public RunTrackingController(RunTrackingRepository t,AuthorizationService z,AuditService a) {
        tracking=t; authorization=z; audit=a;
    }
    public record Start(String simulationId,String scenarioId,Double simulationStartSeconds,Map<String,Object> metadata) {}
    public record Finish(Double simulationEndSeconds) {}
    private void allowed(HttpServletRequest r,String action,String id) {
        authorization.dispatcher(AuthenticationFilter.principal(r),AuthenticationFilter.requestId(r),action,"RUN",id);
    }
    @PostMapping public Map<String,Object> start(@RequestBody Start body,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_START",body.simulationId());
        Map<String,Object> r=tracking.start(body.simulationId(),body.scenarioId(),body.simulationStartSeconds(),body.metadata(),AuthenticationFilter.principal(req).username());
        audit.record(AuthenticationFilter.principal(req),AuthenticationFilter.requestId(req),"RUN_START","RUN",(String)r.get("run_id"),"SUCCESS","explicit run boundary; metadata supplied by operator");
        return r;
    }
    @PostMapping("/{id}/finish") public Map<String,Object> finish(@PathVariable String id,@RequestBody Finish body,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_FINISH",id);
        Map<String,Object> r=tracking.finish(id,body.simulationEndSeconds());
        audit.record(AuthenticationFilter.principal(req),AuthenticationFilter.requestId(req),"RUN_FINISH","RUN",id,"SUCCESS","explicit run boundary");
        return r;
    }
    @GetMapping public List<Map<String,Object>> list(@RequestParam String simulationId,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_LIST",simulationId); return tracking.list(simulationId);
    }
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_READ",id); return tracking.run(id);
    }
    @GetMapping("/{id}/events") public List<Map<String,Object>> events(@PathVariable String id,
            @RequestParam(defaultValue="0") long afterMs,@RequestParam(defaultValue="") String afterId,
            @RequestParam(required=false) Integer vehicleId,@RequestParam(required=false) String commandId,
            @RequestParam(defaultValue="100") int limit,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_EVENTS",id); return tracking.events(id,afterMs,afterId,vehicleId,commandId,limit);
    }
    @GetMapping("/{id}/evidence") public Map<String,Object> evidence(@PathVariable String id,@RequestParam String eventId,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_EVIDENCE",id); return tracking.evidence(id,eventId);
    }
    @GetMapping("/{id}/summary") public Map<String,Object> summary(@PathVariable String id,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_SUMMARY",id); return tracking.summary(id);
    }
    @GetMapping("/{id}/replay") public Map<String,Object> replay(@PathVariable String id,@RequestParam long atWallTimeMs,HttpServletRequest req) throws Exception {
        allowed(req,"RUN_REPLAY",id); return tracking.replay(id,atWallTimeMs);
    }
}
