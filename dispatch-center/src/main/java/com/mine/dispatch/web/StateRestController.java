package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.AuthorizationService;
import com.mine.dispatch.capability.monitoring.StateViewService;
import com.mine.dispatch.capability.storage.DatabaseRepository;
import com.mine.dispatch.model.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.sql.SQLException;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class StateRestController {
    private final StateViewService views;private final DatabaseRepository db;private final AuthorizationService authz;
    public StateRestController(StateViewService v,DatabaseRepository d,AuthorizationService a){views=v;db=d;authz=a;}
    @GetMapping("/simulations") public List<SimulationSummary> simulations(HttpServletRequest r){AuthPrincipal p=AuthenticationFilter.principal(r);authz.dispatcher(p,AuthenticationFilter.requestId(r),"SIMULATION_LIST","SYSTEM","*");return views.summaries();}
    @GetMapping("/simulations/{sim}/snapshot") public SimulationSnapshot snapshot(@PathVariable String sim,HttpServletRequest r){AuthPrincipal p=AuthenticationFilter.principal(r);authz.simulation(p,sim,AuthenticationFilter.requestId(r),"SNAPSHOT_READ");return StateWebSocketHandler.project(views.snapshot(sim),p);}
    @GetMapping("/simulations/{sim}/history/vehicles/{id}") public List<Map<String,Object>> vehicles(@PathVariable String sim,@PathVariable int id,@RequestParam(defaultValue="0")long fromMs,@RequestParam(defaultValue="9223372036854775807")long toMs,@RequestParam(defaultValue="500")int limit,HttpServletRequest r)throws SQLException{authz.vehicle(AuthenticationFilter.principal(r),sim,id,AuthenticationFilter.requestId(r),"VEHICLE_HISTORY_READ");return db.vehicleHistory(sim,id,fromMs,toMs,clamp(limit));}
    @GetMapping("/simulations/{sim}/history/shovels/{id}") public List<Map<String,Object>> shovels(@PathVariable String sim,@PathVariable int id,@RequestParam(defaultValue="0")long fromMs,@RequestParam(defaultValue="9223372036854775807")long toMs,@RequestParam(defaultValue="500")int limit,HttpServletRequest r)throws SQLException{authz.shovel(AuthenticationFilter.principal(r),sim,id,AuthenticationFilter.requestId(r),"SHOVEL_HISTORY_READ");return db.shovelHistory(sim,id,fromMs,toMs,clamp(limit));}
    @GetMapping("/simulations/{sim}/history/maps") public List<Map<String,Object>> maps(@PathVariable String sim,@RequestParam(defaultValue="20")int limit,HttpServletRequest r)throws SQLException{authz.simulation(AuthenticationFilter.principal(r),sim,AuthenticationFilter.requestId(r),"MAP_HISTORY_READ");return db.mapHistory(sim,clamp(limit));}
    @ExceptionHandler(SQLException.class) public ResponseEntity<Map<String,String>> error(SQLException e){return ResponseEntity.internalServerError().body(Map.of("error","database_query_failed","message",e.getMessage()));}private static int clamp(int n){return Math.max(1,Math.min(5000,n));}
}
