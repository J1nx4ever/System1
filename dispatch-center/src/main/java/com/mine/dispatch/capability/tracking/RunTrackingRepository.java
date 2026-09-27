package com.mine.dispatch.capability.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.production.DispatchProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Read-only projections over existing business evidence; no control dependencies. */
@Repository
@DependsOn("databaseRepository")
public class RunTrackingRepository {
    private final String url;
    private final ObjectMapper json;

    public RunTrackingRepository(DispatchProperties properties, ObjectMapper json) {
        this.url = properties.getStorage().getJdbcUrl();
        this.json = json;
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }

    @PostConstruct
    public void migrate() throws Exception {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                for (String file : List.of("V5__run_context.sql", "V5__run_events.sql")) {
                    String sql;
                    try (var input = new ClassPathResource("db/migration/" + file).getInputStream()) {
                        sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    try (Statement s = c.createStatement()) {
                        for (String part : sql.split(";")) if (!part.isBlank()) s.execute(part);
                    }
                }
                try (PreparedStatement s = c.prepareStatement("INSERT OR IGNORE INTO schema_migrations VALUES ('5','run_tracking',?)")) {
                    s.setLong(1, System.currentTimeMillis()); s.executeUpdate();
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    public Map<String,Object> start(String sim, String scenario, Double simStart,
                                    Map<String,Object> metadata, String actor) throws Exception {
        if (sim == null || !sim.matches("[A-Za-z0-9_-]{1,64}")) bad("invalid simulationId");
        finite(simStart);
        String id = UUID.randomUUID().toString();
        try (Connection c = open(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO run_context VALUES (?,?,?,?,NULL,?,NULL,?,?)")) {
            s.setString(1,id); s.setString(2,sim); s.setString(3,scenario);
            s.setLong(4,System.currentTimeMillis()); s.setObject(5,simStart);
            s.setString(6,json.writeValueAsString(metadata == null ? Map.of() : metadata));
            s.setString(7,actor);
            try { s.executeUpdate(); }
            catch (SQLException e) {
                if (e.getErrorCode() == 19) throw new ResponseStatusException(HttpStatus.CONFLICT,"instance already has an active run");
                throw e;
            }
        }
        return run(id);
    }

    public Map<String,Object> finish(String id, Double simEnd) throws Exception {
        finite(simEnd);
        Map<String,Object> r = run(id);
        if (simEnd != null && r.get("simulation_start_seconds") instanceof Number n && simEnd < n.doubleValue()) bad("simulation time moves backwards");
        long now=System.currentTimeMillis();
        long started=((Number)r.get("started_at_ms")).longValue();
        Double observed=latestSimulationTime((String)r.get("simulation_id"),started,now);
        if(observed!=null&&(simEnd==null||observed>simEnd))simEnd=observed;
        try (Connection c = open(); PreparedStatement s = c.prepareStatement(
                "UPDATE run_context SET ended_at_ms=?, simulation_end_seconds=? WHERE run_id=? AND ended_at_ms IS NULL")) {
            s.setLong(1,now); s.setObject(2,simEnd); s.setString(3,id); s.executeUpdate();
        }
        return run(id);
    }

    private Double latestSimulationTime(String simulationId,long started,long ended) throws SQLException {
        String sql="SELECT MAX(sim_time_seconds) FROM ("
                +"SELECT sim_time_seconds FROM map_state_history WHERE simulation_id=? AND received_at_ms BETWEEN ? AND ? UNION ALL "
                +"SELECT sim_time_seconds FROM vehicle_state_history WHERE simulation_id=? AND received_at_ms BETWEEN ? AND ? UNION ALL "
                +"SELECT sim_time_seconds FROM shovel_state_history WHERE simulation_id=? AND received_at_ms BETWEEN ? AND ? UNION ALL "
                +"SELECT sim_time_seconds FROM production_event WHERE simulation_id=? AND sent_wall_time_ms BETWEEN ? AND ?)";
        try(Connection c=open();PreparedStatement s=c.prepareStatement(sql)) {
            for(int i=0;i<4;i++){int p=i*3+1;s.setString(p,simulationId);s.setLong(p+1,started);s.setLong(p+2,ended);}
            try(ResultSet r=s.executeQuery()){if(!r.next())return null;double value=r.getDouble(1);return r.wasNull()?null:value;}
        }
    }

    public Map<String,Object> run(String id) throws Exception {
        List<Map<String,Object>> rows = query("SELECT * FROM run_context WHERE run_id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"run not found");
        Map<String,Object> r = rows.get(0);
        r.put("metadata",json.readTree((String) r.remove("metadata_json")));
        return r;
    }

    public List<Map<String,Object>> list(String sim) throws Exception {
        return query("SELECT * FROM run_context WHERE simulation_id=? ORDER BY started_at_ms DESC LIMIT 100",sim);
    }

    private long end(Map<String,Object> r) {
        return r.get("ended_at_ms") instanceof Number n ? n.longValue() : System.currentTimeMillis();
    }

    public List<Map<String,Object>> events(String id, long afterMs, String afterId,
                                          Integer vehicle, String command, int limit) throws Exception {
        return events(id, afterMs, afterId, vehicle, command, limit, false);
        }

        public List<Map<String,Object>> events(String id, long afterMs, String afterId,
                          Integer vehicle, String command, int limit,
                          boolean latestWindow) throws Exception {
        if (limit < 1 || limit > 500) bad("limit must be 1..500");
        Map<String,Object> r = run(id);
        String sql = "SELECT * FROM run_event_projection WHERE simulation_id=? AND CASE WHEN source='decision_trace' THEN (SELECT created_at_ms FROM decision_trace d WHERE d.decision_id=source_id) ELSE wall_time_ms END >=? AND CASE WHEN source='decision_trace' THEN (SELECT created_at_ms FROM decision_trace d WHERE d.decision_id=source_id) ELSE wall_time_ms END <? AND (source<>'production_event' OR (SELECT run_id FROM production_event p WHERE p.event_id=source_id)=?)"
                + " AND (wall_time_ms>? OR (wall_time_ms=? AND event_id>?))"
                + (vehicle == null ? "" : " AND object_type='VEHICLE' AND object_id=?")
                + (command == null ? "" : " AND command_id=?")
            + " ORDER BY wall_time_ms " + (latestWindow ? "DESC" : "ASC")
            + ",event_id " + (latestWindow ? "DESC" : "ASC") + " LIMIT ?";
        List<Object> args = new ArrayList<>(Arrays.asList(r.get("simulation_id"),r.get("started_at_ms"),end(r),id,afterMs,afterMs,afterId));
        if (vehicle != null) args.add(vehicle);
        if (command != null) args.add(command);
        args.add(limit);
        List<Map<String,Object>> rows = query(sql,args.toArray());
        if (latestWindow) Collections.reverse(rows);
        for (Map<String,Object> event : rows) {
            event.put("run_id",id); event.put("related_task_id",null); event.put("related_cycle_id",null);
            if ("production_event".equals(event.get("source"))) {
                var sourceRows = query("SELECT cycle_id FROM production_event WHERE event_id=?",event.get("source_id"));
                event.put("related_cycle_id",sourceRows.get(0).get("cycle_id"));
            }
            event.put("event_schema_version",1);
            event.put("evidence_reference",event.get("source") + ":" + event.get("source_id"));
        }
        return rows;
    }

    public Map<String,Object> evidence(String id, String eventId) throws Exception {
        Map<String,Object> r = run(id);
        List<Map<String,Object>> rows = query("SELECT * FROM run_event_projection WHERE event_id=? AND simulation_id=? AND CASE WHEN source='decision_trace' THEN (SELECT created_at_ms FROM decision_trace d WHERE d.decision_id=source_id) ELSE wall_time_ms END >=? AND CASE WHEN source='decision_trace' THEN (SELECT created_at_ms FROM decision_trace d WHERE d.decision_id=source_id) ELSE wall_time_ms END <? AND (source<>'production_event' OR (SELECT run_id FROM production_event p WHERE p.event_id=source_id)=?)",
                eventId,r.get("simulation_id"),r.get("started_at_ms"),end(r),id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"event not found in run");
        Map<String,Object> e = rows.get(0);
        String table = (String)e.get("source");
        String key = switch(table) {
            case "decision_trace" -> "decision_id";
            case "production_event" -> "event_id";
            case "terminal_action" -> "action_id";
            case "operational_alert" -> "alert_id";
            case "vehicle_state_history", "shovel_state_history", "map_state_history" -> "id";
            default -> throw new IllegalStateException("unsupported evidence source");
        };
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("event",e);
        out.put("sourceRecord",query("SELECT * FROM " + table + " WHERE " + key + "=?",e.get("source_id")));
        out.put("evidenceScope","Current source record; only event time/result are historical. No fabricated model reason.");
        return out;
    }

    public Map<String,Object> summary(String id) throws Exception {
        Map<String,Object> r = run(id);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("runId",id); out.put("summaryType","RUN_STATISTICS");
        out.put("counts",query("SELECT event_type, count(*) AS recorded_count FROM run_event_projection WHERE simulation_id=? AND CASE WHEN source='decision_trace' THEN (SELECT created_at_ms FROM decision_trace d WHERE d.decision_id=source_id) ELSE wall_time_ms END >=? AND CASE WHEN source='decision_trace' THEN (SELECT created_at_ms FROM decision_trace d WHERE d.decision_id=source_id) ELSE wall_time_ms END <? AND (source<>'production_event' OR (SELECT run_id FROM production_event p WHERE p.event_id=source_id)=?) GROUP BY event_type",
                r.get("simulation_id"),r.get("started_at_ms"),end(r),id));
        out.put("productionSummary",null);
        out.put("limitations",List.of("No verified loading/unloading cycle evidence", "Decision count is not model-call count", "State count is persisted samples, not MQTT received messages", "Task and cycle IDs not yet collected", "Run boundaries use recorded wall-clock time, not source-run tagging"));
        return out;
    }

    /** As-of receipt time, never interpolates positions or invokes inference. */
    public Map<String,Object> replay(String id, long atMs) throws Exception {
        Map<String,Object> r = run(id);
        long start = ((Number)r.get("started_at_ms")).longValue();
        if (atMs < start || atMs >= end(r)) bad("replay timestamp outside run");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("runId",id); out.put("atWallTimeMs",atMs); out.put("interpolated",false);
        for (String type : List.of("vehicle", "shovel", "map")) {
            if ("map".equals(type)) {
                out.put(type,query("SELECT * FROM map_state_history WHERE simulation_id=? AND received_at_ms<=? ORDER BY received_at_ms DESC,id DESC LIMIT 1",
                        r.get("simulation_id"),atMs));
                continue;
            }
            String table = type + "_state_history";
            String partition = type + "_id";
            out.put(type,query("SELECT * FROM (SELECT *, row_number() OVER (PARTITION BY " + partition
                    + " ORDER BY received_at_ms DESC,id DESC) AS sample_rank FROM " + table
                    + " WHERE simulation_id=? AND received_at_ms>=? AND received_at_ms<=?) WHERE sample_rank=1",
                    r.get("simulation_id"),start,atMs));
        }
        out.put("completeness","Recorded samples only; missing samples remain unknown. Use received_at_ms to assess age.");
        return out;
    }

    private List<Map<String,Object>> query(String sql,Object... args) throws SQLException {
        try (Connection c = open(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
            List<Map<String,Object>> rows = new ArrayList<>();
            try (ResultSet r=s.executeQuery()) {
                ResultSetMetaData m=r.getMetaData();
                while (r.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for (int i=1;i<=m.getColumnCount();i++) row.put(m.getColumnLabel(i),r.getObject(i));
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private void finite(Double v) { if (v != null && (!Double.isFinite(v) || v < 0)) bad("simulation time must be finite and nonnegative"); }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}

