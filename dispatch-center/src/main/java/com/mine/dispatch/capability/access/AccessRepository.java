package com.mine.dispatch.capability.access;

import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.model.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.*;

@Repository
@DependsOn("databaseRepository")
public class AccessRepository {
    private final String jdbcUrl;
    public AccessRepository(DispatchProperties properties) { jdbcUrl = properties.getStorage().getJdbcUrl(); }

    public Optional<UserRow> user(String username) throws SQLException {
        try (Connection c=open(); PreparedStatement s=c.prepareStatement("SELECT * FROM app_user WHERE username=? AND enabled=1")) {
            s.setString(1, username); try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(userRow(r)):Optional.empty();}
        }
    }
    public void createUser(String username, String passwordHash, String role, String simulationId, Integer subjectId) throws SQLException {
        try (Connection c=open(); PreparedStatement s=c.prepareStatement("INSERT INTO app_user(user_id,username,password_hash,role,simulation_id,subject_id,created_at_ms) VALUES(?,?,?,?,?,?,?) ON CONFLICT(username) DO UPDATE SET password_hash=excluded.password_hash,role=excluded.role,simulation_id=excluded.simulation_id,subject_id=excluded.subject_id,enabled=1")) {
            s.setString(1,UUID.randomUUID().toString());s.setString(2,username);s.setString(3,passwordHash);s.setString(4,role);
            nullableString(s,5,simulationId);nullableInt(s,6,subjectId);s.setLong(7,System.currentTimeMillis());s.executeUpdate();
        }
    }
    public void createSession(String hash, String userId, long now, long expires) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("INSERT INTO auth_session(token_hash,user_id,created_at_ms,expires_at_ms,last_seen_at_ms) VALUES(?,?,?,?,?)")){
            s.setString(1,hash);s.setString(2,userId);s.setLong(3,now);s.setLong(4,expires);s.setLong(5,now);s.executeUpdate();
        }
    }
    public Optional<AuthPrincipal> session(String hash, long now) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT u.* FROM auth_session a JOIN app_user u ON u.user_id=a.user_id WHERE a.token_hash=? AND a.expires_at_ms>? AND u.enabled=1")){
            s.setString(1,hash);s.setLong(2,now);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();UserRow u=userRow(r);return Optional.of(u.principal());}
        }
    }
    public void deleteSession(String hash) throws SQLException { try(Connection c=open();PreparedStatement s=c.prepareStatement("DELETE FROM auth_session WHERE token_hash=?")){s.setString(1,hash);s.executeUpdate();} }
    public void audit(AuditEntry a) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("INSERT INTO audit_log VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
            int i=1;s.setString(i++,a.auditId());s.setString(i++,a.requestId());nullableString(s,i++,a.actorUserId());s.setString(i++,a.actorUsername());s.setString(i++,a.actorRole());s.setString(i++,a.operation());s.setString(i++,a.objectType());s.setString(i++,a.objectId());s.setLong(i++,a.occurredAtMs());s.setString(i++,a.result());s.setString(i,a.detail());s.executeUpdate();
        }
    }
    public List<AuditEntry> audits(int limit) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT * FROM audit_log ORDER BY occurred_at_ms DESC LIMIT ?")){s.setInt(1,limit);List<AuditEntry> out=new ArrayList<>();try(ResultSet r=s.executeQuery()){while(r.next())out.add(new AuditEntry(r.getString("audit_id"),r.getString("request_id"),r.getString("actor_user_id"),r.getString("actor_username"),r.getString("actor_role"),r.getString("operation"),r.getString("object_type"),r.getString("object_id"),r.getLong("occurred_at_ms"),r.getString("result"),r.getString("detail")));}return out;}
    }
    public void insertAction(TerminalAction a) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("INSERT INTO terminal_action VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")){int i=1;s.setString(i++,a.actionId());s.setString(i++,a.simulationId());s.setString(i++,a.actorUserId());s.setString(i++,a.actorUsername());s.setString(i++,a.actorRole());s.setString(i++,a.objectType());s.setInt(i++,a.objectId());s.setString(i++,a.actionType());nullableString(s,i++,a.commandId());s.setString(i++,a.detail());s.setLong(i++,a.createdAtMs());s.setString(i++,a.result());s.setString(i,a.resultDetail());s.executeUpdate();}
    }
    public List<TerminalAction> actions(String sim,String type,Integer id,int limit) throws SQLException {
        return actions(sim,type,id,limit,null);
    }
    public List<TerminalAction> actions(String sim,String type,Integer id,int limit,String runId) throws SQLException {
        String sql="SELECT * FROM terminal_action WHERE simulation_id=?"+(type==null?"":" AND object_type=? AND object_id=?")+runFilter("terminal_action",runId)+" ORDER BY created_at_ms DESC LIMIT ?";
        try(Connection c=open();PreparedStatement s=c.prepareStatement(sql)){int i=1;s.setString(i++,sim);if(type!=null){s.setString(i++,type);s.setInt(i++,id);}if(runId!=null&&!"active".equals(runId))s.setString(i++,runId);s.setInt(i,limit);List<TerminalAction> out=new ArrayList<>();try(ResultSet r=s.executeQuery()){while(r.next())out.add(action(r));}return out;}
    }
    public void insertAlert(OperationalAlert a) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("INSERT INTO operational_alert VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){bindAlert(s,a);s.executeUpdate();}
    }
    public Optional<OperationalAlert> alert(String id) throws SQLException {try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT * FROM operational_alert WHERE alert_id=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(alert(r)):Optional.empty();}}}
    public List<OperationalAlert> alerts(String sim,String type,Integer id,int limit) throws SQLException {
        return alerts(sim,type,id,limit,null);
    }
    public List<OperationalAlert> alerts(String sim,String type,Integer id,int limit,String runId) throws SQLException {
        String sql="SELECT * FROM operational_alert WHERE simulation_id=?"+(type==null?"":" AND object_type=? AND object_id=?")+runFilter("operational_alert",runId)+" ORDER BY created_at_ms DESC LIMIT ?";
        try(Connection c=open();PreparedStatement s=c.prepareStatement(sql)){int i=1;s.setString(i++,sim);if(type!=null){s.setString(i++,type);s.setInt(i++,id);}if(runId!=null&&!"active".equals(runId))s.setString(i++,runId);s.setInt(i,limit);List<OperationalAlert> out=new ArrayList<>();try(ResultSet r=s.executeQuery()){while(r.next())out.add(alert(r));}return out;}
    }
    private static String runFilter(String table,String runId){
        if(runId==null)return "";
        return " AND EXISTS (SELECT 1 FROM run_context r WHERE r.simulation_id="+table+".simulation_id AND "
                +("active".equals(runId)?"r.ended_at_ms IS NULL":"r.run_id=?")
                +" AND "+table+".created_at_ms>=r.started_at_ms AND (r.ended_at_ms IS NULL OR "+table+".created_at_ms<r.ended_at_ms))";
    }

    public OperationalAlert transition(String id,String expected,String next,String username,String detail,long now) throws SQLException {
        String assignments=switch(next){case "ACKNOWLEDGED"->"status=?,acknowledged_by=?,acknowledged_at_ms=?,version=version+1";case "PROCESSING"->"status=?,processing_by=?,processing_at_ms=?,version=version+1";default->"status=?,closed_by=?,closed_at_ms=?,resolution=?,version=version+1";};
        try(Connection c=open();PreparedStatement s=c.prepareStatement("UPDATE operational_alert SET "+assignments+" WHERE alert_id=? AND status=?")){int i=1;s.setString(i++,next);s.setString(i++,username);s.setLong(i++,now);if("CLOSED".equals(next))s.setString(i++,detail);s.setString(i++,id);s.setString(i,expected);if(s.executeUpdate()!=1)throw new SQLException("alert transition conflict");}
        return alert(id).orElseThrow();
    }
    private Connection open() throws SQLException {Connection c=DriverManager.getConnection(jdbcUrl);try(Statement s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA foreign_keys=ON");}return c;}
    private static UserRow userRow(ResultSet r)throws SQLException{return new UserRow(r.getString("user_id"),r.getString("username"),r.getString("password_hash"),r.getString("role"),r.getString("simulation_id"),nullableInt(r,"subject_id"));}
    private static TerminalAction action(ResultSet r)throws SQLException{return new TerminalAction(r.getString("action_id"),r.getString("simulation_id"),r.getString("actor_user_id"),r.getString("actor_username"),r.getString("actor_role"),r.getString("object_type"),r.getInt("object_id"),r.getString("action_type"),r.getString("command_id"),r.getString("detail"),r.getLong("created_at_ms"),r.getString("result"),r.getString("result_detail"));}
    private static OperationalAlert alert(ResultSet r)throws SQLException{return new OperationalAlert(r.getString("alert_id"),r.getString("simulation_id"),r.getString("source_action_id"),r.getString("object_type"),r.getInt("object_id"),r.getString("severity"),r.getString("code"),r.getString("message"),r.getString("status"),r.getLong("created_at_ms"),r.getString("acknowledged_by"),nullableLong(r,"acknowledged_at_ms"),r.getString("processing_by"),nullableLong(r,"processing_at_ms"),r.getString("closed_by"),nullableLong(r,"closed_at_ms"),r.getString("resolution"),r.getInt("version"));}
    private static void bindAlert(PreparedStatement s,OperationalAlert a)throws SQLException{int i=1;s.setString(i++,a.alertId());s.setString(i++,a.simulationId());nullableString(s,i++,a.sourceActionId());s.setString(i++,a.objectType());s.setInt(i++,a.objectId());s.setString(i++,a.severity());s.setString(i++,a.code());s.setString(i++,a.message());s.setString(i++,a.status());s.setLong(i++,a.createdAtMs());nullableString(s,i++,a.acknowledgedBy());nullableLong(s,i++,a.acknowledgedAtMs());nullableString(s,i++,a.processingBy());nullableLong(s,i++,a.processingAtMs());nullableString(s,i++,a.closedBy());nullableLong(s,i++,a.closedAtMs());s.setString(i++,a.resolution());s.setInt(i,a.version());}
    private static Integer nullableInt(ResultSet r,String n)throws SQLException{int v=r.getInt(n);return r.wasNull()?null:v;}private static Long nullableLong(ResultSet r,String n)throws SQLException{long v=r.getLong(n);return r.wasNull()?null:v;}
    private static void nullableInt(PreparedStatement s,int i,Integer v)throws SQLException{if(v==null)s.setNull(i,Types.INTEGER);else s.setInt(i,v);}private static void nullableLong(PreparedStatement s,int i,Long v)throws SQLException{if(v==null)s.setNull(i,Types.BIGINT);else s.setLong(i,v);}private static void nullableString(PreparedStatement s,int i,String v)throws SQLException{if(v==null)s.setNull(i,Types.VARCHAR);else s.setString(i,v);}
    public record UserRow(String userId,String username,String passwordHash,String role,String simulationId,Integer subjectId){public AuthPrincipal principal(){return new AuthPrincipal(userId,username,role,simulationId,subjectId);}}
}
