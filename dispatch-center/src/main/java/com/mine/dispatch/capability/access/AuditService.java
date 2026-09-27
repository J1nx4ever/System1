package com.mine.dispatch.capability.access;

import com.mine.dispatch.model.AuditEntry;
import com.mine.dispatch.model.AuthPrincipal;
import org.springframework.stereotype.Service;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
public class AuditService {
    private final AccessRepository repository;
    public AuditService(AccessRepository repository){this.repository=repository;}
    public void record(AuthPrincipal p,String requestId,String operation,String objectType,String objectId,String result,String detail){
        try{repository.audit(new AuditEntry(UUID.randomUUID().toString(),requestId==null?UUID.randomUUID().toString():requestId,p==null?null:p.userId(),p==null?"anonymous":p.username(),p==null?"ANONYMOUS":p.role(),operation,objectType,objectId,System.currentTimeMillis(),result,detail==null?"":detail));}catch(SQLException e){throw new IllegalStateException("audit persistence failed",e);}
    }
    public List<AuditEntry> list(int limit){try{return repository.audits(Math.max(1,Math.min(500,limit)));}catch(SQLException e){throw new IllegalStateException("audit query failed",e);}}
}
