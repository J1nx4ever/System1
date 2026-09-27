package com.mine.dispatch.capability.access;

import com.mine.dispatch.capability.decision.DecisionTraceRepository;
import com.mine.dispatch.model.*;
import com.mine.dispatch.web.RealtimeHub;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.sql.SQLException;
import java.util.*;

@Service
public class TerminalOperationService {
    private final AccessRepository repo;private final DecisionTraceRepository decisions;private final AuditService audit;private final RealtimeHub realtime;
    public TerminalOperationService(AccessRepository r,DecisionTraceRepository d,AuditService a,RealtimeHub h){repo=r;decisions=d;audit=a;realtime=h;}
    public TerminalAction create(String sim,AuthPrincipal p,TerminalActionRequest request,String requestId){
        if(!List.of("VEHICLE","SHOVEL").contains(p.role())||!sim.equals(p.simulationId()))return reject(sim,p,request,requestId,"terminal identity mismatch",HttpStatus.FORBIDDEN);
        String type=request.actionType(),detail=Optional.ofNullable(request.detail()).orElse("").strip(),command=blank(request.commandId());
        if(!"TASK_CONFIRMATION".equals(type)&&detail.isBlank())return reject(sim,p,request,requestId,"detail is required",HttpStatus.BAD_REQUEST);
        if("TASK_CONFIRMATION".equals(type)){
            if(command==null)return reject(sim,p,request,requestId,"commandId is required",HttpStatus.BAD_REQUEST);
            try{DecisionTrace trace=decisions.findByCommand(command).orElse(null);boolean matches=trace!=null&&sim.equals(trace.simulationId())&&("VEHICLE".equals(p.role())?Objects.equals(trace.vehicleId(),p.subjectId()):Objects.equals(trace.shovelId(),p.subjectId()));if(!matches)return reject(sim,p,request,requestId,"task does not belong to terminal identity",HttpStatus.FORBIDDEN);}catch(SQLException e){throw new IllegalStateException(e);}
        }
        TerminalAction action=new TerminalAction(UUID.randomUUID().toString(),sim,p.userId(),p.username(),p.role(),p.role(),p.subjectId(),type,command,detail,System.currentTimeMillis(),"ACCEPTED","validated and persisted");
        try{repo.insertAction(action);if("EXCEPTION".equals(type))createAlert(action,request);audit.record(p,requestId,"TERMINAL_"+type,p.role(),String.valueOf(p.subjectId()),"SUCCESS",action.actionId());realtime.broadcast(RealtimeEvent.of("terminalAction",sim,action));return action;}catch(SQLException e){audit.record(p,requestId,"TERMINAL_"+type,p.role(),String.valueOf(p.subjectId()),"FAILED",e.getMessage());throw new AccessDeniedException(HttpStatus.CONFLICT,"terminal_action_conflict","操作重复或状态冲突");}
    }
    public List<TerminalAction> list(String sim,AuthPrincipal p,int limit){try{return repo.actions(sim,p.dispatcher()?null:p.role(),p.dispatcher()?null:p.subjectId(),Math.max(1,Math.min(200,limit)));}catch(SQLException e){throw new IllegalStateException(e);}}
    private void createAlert(TerminalAction a,TerminalActionRequest r)throws SQLException{OperationalAlert alert=new OperationalAlert(UUID.randomUUID().toString(),a.simulationId(),a.actionId(),a.objectType(),a.objectId(),Optional.ofNullable(r.severity()).orElse("WARNING"),Optional.ofNullable(blank(r.code())).orElse("TERMINAL_EXCEPTION"),a.detail(),"OPEN",System.currentTimeMillis(),null,null,null,null,null,null,"",0);repo.insertAlert(alert);realtime.broadcast(RealtimeEvent.of("alert",a.simulationId(),alert));}
    private TerminalAction reject(String sim,AuthPrincipal p,TerminalActionRequest r,String requestId,String reason,HttpStatus status){TerminalAction a=new TerminalAction(UUID.randomUUID().toString(),sim,p.userId(),p.username(),p.role(),p.objectType(),p.subjectId()==null?-1:p.subjectId(),r.actionType(),blank(r.commandId()),Optional.ofNullable(r.detail()).orElse(""),System.currentTimeMillis(),"REJECTED",reason);try{repo.insertAction(a);}catch(SQLException ignored){}audit.record(p,requestId,"TERMINAL_"+r.actionType(),p.objectType(),p.objectId(),"DENIED",reason);throw new AccessDeniedException(status,"terminal_action_rejected",reason);}
    private static String blank(String v){return v==null||v.isBlank()?null:v.strip();}
}
