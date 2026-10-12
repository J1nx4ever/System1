package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.*;
import com.mine.dispatch.model.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/simulations/{simulationId}")
public class OperationsController {
    private final TerminalOperationService terminals;private final AlertService alerts;private final AuditService audit;private final AuthorizationService authz;
    public OperationsController(TerminalOperationService t,AlertService a,AuditService u,AuthorizationService z){terminals=t;alerts=a;audit=u;authz=z;}
    @PostMapping("/terminal-actions") public TerminalAction action(@PathVariable String simulationId,@Valid @RequestBody TerminalActionRequest body,HttpServletRequest req){AuthPrincipal p=AuthenticationFilter.principal(req);authz.simulation(p,simulationId,AuthenticationFilter.requestId(req),"TERMINAL_ACTION");return terminals.create(simulationId,p,body,AuthenticationFilter.requestId(req));}
    @GetMapping("/terminal-actions") public List<TerminalAction> actions(@PathVariable String simulationId,@RequestParam(defaultValue="50")int limit,@RequestParam(required=false)String runId,HttpServletRequest req){AuthPrincipal p=AuthenticationFilter.principal(req);authz.simulation(p,simulationId,AuthenticationFilter.requestId(req),"TERMINAL_ACTION_LIST");return terminals.list(simulationId,p,limit,runId);}
    @GetMapping("/alerts") public List<OperationalAlert> alerts(@PathVariable String simulationId,@RequestParam(defaultValue="100")int limit,@RequestParam(required=false)String runId,HttpServletRequest req){AuthPrincipal p=AuthenticationFilter.principal(req);authz.simulation(p,simulationId,AuthenticationFilter.requestId(req),"ALERT_LIST");return alerts.list(simulationId,p,limit,runId);}
    @PostMapping("/alerts/{alertId}/{transition}") public OperationalAlert transition(@PathVariable String simulationId,@PathVariable String alertId,@PathVariable String transition,@Valid @RequestBody AlertTransitionRequest body,HttpServletRequest req){return alerts.transition(simulationId,alertId,transition,body.detail(),AuthenticationFilter.principal(req),AuthenticationFilter.requestId(req));}
    @GetMapping("/audit") public List<AuditEntry> audit(@PathVariable String simulationId,@RequestParam(defaultValue="100")int limit,HttpServletRequest req){AuthPrincipal p=AuthenticationFilter.principal(req);authz.dispatcher(p,AuthenticationFilter.requestId(req),"AUDIT_LIST","SIMULATION",simulationId);return audit.list(limit);}
}
