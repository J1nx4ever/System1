package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.*;
import com.mine.dispatch.capability.manualdispatch.*;
import com.mine.dispatch.model.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/simulations/{simulationId}/manual-dispatch")
public class ManualDispatchController {
    private final ManualDispatchService dispatch;private final AuthorizationService authz;private final AuditService audit;
    public ManualDispatchController(ManualDispatchService d,AuthorizationService z,AuditService a){dispatch=d;authz=z;audit=a;}
    @PostMapping public ResponseEntity<ManualDispatchCommand> create(@PathVariable String simulationId,@Valid @RequestBody ManualDispatchRequest body,HttpServletRequest req){AuthPrincipal p=AuthenticationFilter.principal(req);String rid=AuthenticationFilter.requestId(req);authz.dispatcher(p,rid,"MANUAL_DISPATCH","VEHICLE",String.valueOf(body.vehicleId()));try{ManualDispatchCommand c=dispatch.create(simulationId,body);audit.record(p,rid,"MANUAL_DISPATCH","VEHICLE",String.valueOf(body.vehicleId()),c.status().equals("SENT")?"SUCCESS":"FAILED",c.commandId()+" "+c.status());return ResponseEntity.status(c.status().equals("SENT")?202:503).body(c);}catch(RuntimeException e){audit.record(p,rid,"MANUAL_DISPATCH","VEHICLE",String.valueOf(body.vehicleId()),"FAILED",e.getMessage());throw e;}}
    @GetMapping public List<ManualDispatchCommand> list(@PathVariable String simulationId,@RequestParam(required=false)Integer vehicleId,@RequestParam(defaultValue="30")int limit,HttpServletRequest req){AuthPrincipal p=AuthenticationFilter.principal(req);if(vehicleId==null)authz.dispatcher(p,AuthenticationFilter.requestId(req),"MANUAL_DISPATCH_LIST","SIMULATION",simulationId);else authz.vehicle(p,simulationId,vehicleId,AuthenticationFilter.requestId(req),"MANUAL_DISPATCH_LIST");return dispatch.list(simulationId,vehicleId,limit);}
    @ExceptionHandler(ManualDispatchException.class) public ResponseEntity<Map<String,String>> error(ManualDispatchException e){return ResponseEntity.status(e.status()).body(Map.of("error",e.code(),"message",e.getMessage()));}
}
