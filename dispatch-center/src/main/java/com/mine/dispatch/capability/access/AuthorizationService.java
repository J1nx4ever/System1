package com.mine.dispatch.capability.access;

import com.mine.dispatch.model.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
    private final AuditService audit;
    public AuthorizationService(AuditService audit){this.audit=audit;}
    public void dispatcher(AuthPrincipal p,String requestId,String operation,String type,String id){if(p==null||!p.dispatcher())deny(p,requestId,operation,type,id,"dispatcher role required");}
    public void simulation(AuthPrincipal p,String sim,String requestId,String operation){if(p==null)deny(null,requestId,operation,"SIMULATION",sim,"authentication required");if(!p.dispatcher()&&!sim.equals(p.simulationId()))deny(p,requestId,operation,"SIMULATION",sim,"cross-simulation access denied");}
    public void vehicle(AuthPrincipal p,String sim,int id,String requestId,String operation){simulation(p,sim,requestId,operation);if(!p.dispatcher()&&!("VEHICLE".equals(p.role())&&p.subjectId()==id))deny(p,requestId,operation,"VEHICLE",String.valueOf(id),"cross-vehicle access denied");}
    public void shovel(AuthPrincipal p,String sim,int id,String requestId,String operation){simulation(p,sim,requestId,operation);if(!p.dispatcher()&&!("SHOVEL".equals(p.role())&&p.subjectId()==id))deny(p,requestId,operation,"SHOVEL",String.valueOf(id),"cross-shovel access denied");}
    private void deny(AuthPrincipal p,String requestId,String operation,String type,String id,String detail){audit.record(p,requestId,operation,type,id,"DENIED",detail);throw new AccessDeniedException(HttpStatus.FORBIDDEN,"access_denied",detail);}
}
