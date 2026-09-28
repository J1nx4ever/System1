package com.mine.dispatch.capability.access;

import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.model.TerminalMessage;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

@Repository
@DependsOn("databaseRepository")
public class TerminalMessageRepository {
    private final String url;
    public TerminalMessageRepository(DispatchProperties properties) { url=properties.getStorage().getJdbcUrl(); }

    @PostConstruct public void migrate() throws Exception {
        String sql;
        try(var input=new ClassPathResource("db/migration/V7__terminal_messages.sql").getInputStream()) {
            sql=new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                try(Statement s=c.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);}
                try(PreparedStatement s=c.prepareStatement("INSERT OR IGNORE INTO schema_migrations VALUES('7','terminal_messages',?)")) {
                    s.setLong(1,System.currentTimeMillis());s.executeUpdate();
                }
                c.commit();
            } catch(Exception e){c.rollback();throw e;}
        }
    }
    public void insert(TerminalMessage m) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("INSERT INTO terminal_message VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,m.messageId());s.setString(2,m.simulationId());s.setString(3,m.objectType());s.setInt(4,m.objectId());
            s.setString(5,m.direction());s.setString(6,m.senderRole());s.setString(7,m.senderName());s.setString(8,m.body());
            s.setLong(9,m.createdAtMs());s.setString(10,m.status());s.setString(11,m.replyToMessageId());s.setString(12,m.statusDetail());
            // Keep compatibility if a future migration adds optional columns.
            s.executeUpdate();
        }
    }
    public void markSent(String id) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("UPDATE terminal_message SET status='SENT' WHERE message_id=? AND status='PENDING'")) {
            s.setString(1,id);s.executeUpdate();
        }
    }
    public void updateStatus(String id,String status,String detail) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("UPDATE terminal_message SET status=?,status_detail=? WHERE message_id=?")) {
            s.setString(1,status);s.setString(2,detail==null?"":detail);s.setString(3,id);s.executeUpdate();
        }
    }
    public Optional<TerminalMessage> find(String id) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT * FROM terminal_message WHERE message_id=?")) {
            s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(row(r)):Optional.empty();}
        }
    }
    public List<TerminalMessage> list(String sim,String type,Integer id,int limit) throws SQLException {
        String sql="SELECT * FROM terminal_message WHERE simulation_id=?"+(type==null?"":" AND object_type=? AND object_id=?")+" ORDER BY created_at_ms DESC LIMIT ?";
        try(Connection c=open();PreparedStatement s=c.prepareStatement(sql)) {
            int i=1;s.setString(i++,sim);if(type!=null){s.setString(i++,type);s.setInt(i++,id);}s.setInt(i,Math.max(1,Math.min(limit,500)));
            List<TerminalMessage> out=new ArrayList<>();try(ResultSet r=s.executeQuery()){while(r.next())out.add(row(r));}
            Collections.reverse(out);return out;
        }
    }
    private Connection open() throws SQLException {Connection c=DriverManager.getConnection(url);try(Statement s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA foreign_keys=ON");}return c;}
    private static TerminalMessage row(ResultSet r)throws SQLException{return new TerminalMessage(r.getString("message_id"),r.getString("simulation_id"),r.getString("object_type"),r.getInt("object_id"),r.getString("direction"),r.getString("sender_role"),r.getString("sender_name"),r.getString("body"),r.getLong("created_at_ms"),r.getString("status"),r.getString("reply_to_message_id"),r.getString("status_detail"));}
}
