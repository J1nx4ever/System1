package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.AuthorizationService;
import com.mine.dispatch.capability.decision.DecisionTraceService;
import com.mine.dispatch.model.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/simulations/{simulationId}")
public class DecisionTraceController {
    private final DecisionTraceService traces;private final AuthorizationService authz;
    public DecisionTraceController(DecisionTraceService t,AuthorizationService a){traces=t;authz=a;}
    @GetMapping("/decisions") public List<DecisionTrace> all(@PathVariable String simulationId,@RequestParam(required=false)Integer vehicleId,@RequestParam(required=false)Integer shovelId,@RequestParam(required=false)String type,@RequestParam(defaultValue="50")int limit,HttpServletRequest r){AuthPrincipal p=AuthenticationFilter.principal(r);authz.dispatcher(p,AuthenticationFilter.requestId(r),"DECISION_LIST","SIMULATION",simulationId);return traces.list(simulationId,vehicleId,shovelId,type,limit);}
    @GetMapping("/vehicles/{vehicleId}/decisions") public List<DecisionTrace> vehicle(@PathVariable String simulationId,@PathVariable int vehicleId,@RequestParam(required=false)String type,@RequestParam(defaultValue="30")int limit,HttpServletRequest r){authz.vehicle(AuthenticationFilter.principal(r),simulationId,vehicleId,AuthenticationFilter.requestId(r),"VEHICLE_DECISION_READ");return traces.list(simulationId,vehicleId,null,type,limit);}
    @GetMapping("/shovels/{shovelId}/decisions") public List<DecisionTrace> shovel(@PathVariable String simulationId,@PathVariable int shovelId,@RequestParam(defaultValue="30")int limit,HttpServletRequest r){authz.shovel(AuthenticationFilter.principal(r),simulationId,shovelId,AuthenticationFilter.requestId(r),"SHOVEL_DECISION_READ");return traces.list(simulationId,null,shovelId,"TASK",limit);}
}
