package com.mine.dispatch.capability.access;

import com.mine.dispatch.model.*;
import com.mine.dispatch.web.RealtimeHub;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.sql.SQLException;
import java.util.List;

@Service
public class AlertService {
    private final AccessRepository repo;private final AuditService audit;private final RealtimeHub realtime;
    public AlertService(AccessRepository r,AuditService a,RealtimeHub h){repo=r;audit=a;realtime=h;}
    public List<OperationalAlert> list(String sim,AuthPrincipal p,int limit){try{return repo.alerts(sim,p.dispatcher()?null:p.role(),p.dispatcher()?null:p.subjectId(),Math.max(1,Math.min(500,limit)));}catch(SQLException e){throw new IllegalStateException(e);}}
    public OperationalAlert transition(String sim,String id,String action,String detail,AuthPrincipal p,String requestId){
        if(!p.dispatcher()){audit.record(p,requestId,"ALERT_"+action,"ALERT",id,"DENIED","dispatcher role required");throw new AccessDeniedException(HttpStatus.FORBIDDEN,"access_denied","只有调度员可处理告警");}
        String expected,next;switch(action){case "acknowledge"->{expected="OPEN";next="ACKNOWLEDGED";}case "process"->{expected="ACKNOWLEDGED";next="PROCESSING";}case "close"->{expected="PROCESSING";next="CLOSED";}default->throw new AccessDeniedException(HttpStatus.BAD_REQUEST,"invalid_transition","未知告警操作");}
        if("CLOSED".equals(next)&&(detail==null||detail.isBlank()))throw new AccessDeniedException(HttpStatus.BAD_REQUEST,"resolution_required","关闭告警必须填写处理结果");
        try{OperationalAlert current=repo.alert(id).orElseThrow(()->new AccessDeniedException(HttpStatus.NOT_FOUND,"alert_not_found","告警不存在"));if(!sim.equals(current.simulationId()))throw new AccessDeniedException(HttpStatus.NOT_FOUND,"alert_not_found","告警不存在");OperationalAlert updated=repo.transition(id,expected,next,p.username(),detail==null?"":detail.strip(),System.currentTimeMillis());audit.record(p,requestId,"ALERT_"+action.toUpperCase(),"ALERT",id,"SUCCESS",expected+" -> "+next);realtime.broadcast(RealtimeEvent.of("alert",sim,updated));return updated;}catch(SQLException e){audit.record(p,requestId,"ALERT_"+action.toUpperCase(),"ALERT",id,"FAILED",e.getMessage());throw new AccessDeniedException(HttpStatus.CONFLICT,"alert_state_conflict","告警状态不允许该操作");}
    }
}
