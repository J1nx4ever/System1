package com.mine.dispatch.capability.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.model.MapStateView;
import com.mine.dispatch.model.ShovelStateView;
import com.mine.dispatch.model.VehicleStateView;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DatabaseRepository {
    private static final Map<String, String> MIGRATIONS = Map.of(
            "1", "db/migration/V1__initial_schema.sql",
            "2", "db/migration/V2__manual_dispatch.sql",
            "3", "db/migration/V3__decision_trace.sql",
            "4", "db/migration/V4__access_audit_alerts.sql");

    private final DispatchProperties properties;
    private final ObjectMapper objectMapper;
    private Connection connection;

    public DatabaseRepository(DispatchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public synchronized void initialize() throws Exception {
        String jdbcUrl = properties.getStorage().getJdbcUrl();
        createParentDirectory(jdbcUrl);
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (" +
                    "version TEXT PRIMARY KEY, description TEXT NOT NULL, installed_at_ms INTEGER NOT NULL)");
        }
        for (String version : List.of("1", "2", "3", "4")) {
            if (migrationInstalled(version)) continue;
            runMigration(MIGRATIONS.get(version));
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_migrations(version, description, installed_at_ms) VALUES (?, ?, ?)")) {
                statement.setString(1, version);
                statement.setString(2, switch (version) {
                    case "1" -> "initial_schema";
                    case "2" -> "manual_dispatch";
                    case "3" -> "decision_trace";
                    default -> "access_audit_alerts";
                });
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    public synchronized void persist(MapStateView state) throws SQLException, JsonProcessingException {
        String sql = """
                INSERT INTO map_state_history(
                  simulation_id, sequence, sim_time_seconds, received_at_ms,
                  image_file, image_width_px, image_height_px, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.simulationId());
            statement.setLong(2, state.sequence());
            statement.setDouble(3, state.simTimeSeconds());
            statement.setLong(4, state.receivedAtMs());
            statement.setString(5, state.imageFile());
            statement.setInt(6, state.imageWidthPx());
            statement.setInt(7, state.imageHeightPx());
            statement.setString(8, objectMapper.writeValueAsString(state));
            statement.executeUpdate();
        }
    }

    public synchronized void persist(VehicleStateView state) throws SQLException {
        String sql = """
                INSERT INTO vehicle_state_history(
                  simulation_id, vehicle_id, sequence, sim_time_seconds,
                  sent_wall_time_ms, received_at_ms, x, y, speed_mps, loaded,
                  payload_tons, current_node, destination_node, current_segment_id,
                  target_load_site_id, target_dump_site_id, statechart_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, state.simulationId());
            statement.setInt(index++, state.vehicleId());
            statement.setLong(index++, state.sequence());
            statement.setDouble(index++, state.simTimeSeconds());
            statement.setLong(index++, state.sentWallTimeMs());
            statement.setLong(index++, state.receivedAtMs());
            statement.setDouble(index++, state.x());
            statement.setDouble(index++, state.y());
            statement.setDouble(index++, state.speedMps());
            statement.setInt(index++, state.loaded() ? 1 : 0);
            statement.setDouble(index++, state.payloadTons());
            statement.setString(index++, state.currentNode());
            statement.setString(index++, state.destinationNode());
            statement.setString(index++, state.currentSegmentId());
            statement.setInt(index++, state.targetLoadSiteId());
            statement.setInt(index++, state.targetDumpSiteId());
            statement.setString(index, state.statechartState());
            statement.executeUpdate();
        }
    }

    public synchronized void persist(ShovelStateView state) throws SQLException {
        String sql = """
                INSERT INTO shovel_state_history(
                  simulation_id, shovel_id, sequence, sim_time_seconds, received_at_ms,
                  shovel_name, x, y, available, busy, waiting_trucks, trucks_in_service,
                  ore_grade, remaining_tons, produces_ore
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, state.simulationId());
            statement.setInt(index++, state.shovelId());
            statement.setLong(index++, state.sequence());
            statement.setDouble(index++, state.simTimeSeconds());
            statement.setLong(index++, state.receivedAtMs());
            statement.setString(index++, state.shovelName());
            statement.setDouble(index++, state.x());
            statement.setDouble(index++, state.y());
            statement.setInt(index++, state.available() ? 1 : 0);
            statement.setInt(index++, state.busy() ? 1 : 0);
            statement.setInt(index++, state.waitingTrucks());
            statement.setInt(index++, state.trucksInService());
            statement.setDouble(index++, state.oreGrade());
            statement.setDouble(index++, state.remainingTons());
            statement.setInt(index, state.producesOre() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    public synchronized void rejection(String topic, String reason, int payloadSize) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO message_rejections(topic, received_at_ms, reason, payload_size) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, topic);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, reason);
            statement.setInt(4, payloadSize);
            statement.executeUpdate();
        }
    }

    public synchronized List<Map<String, Object>> vehicleHistory(
            String simulationId, int vehicleId, long fromMs, long toMs, int limit) throws SQLException {
        String sql = """
                SELECT simulation_id, vehicle_id, sequence, sim_time_seconds,
                       sent_wall_time_ms, received_at_ms, x, y, speed_mps, loaded,
                       payload_tons, current_node, destination_node, current_segment_id,
                       target_load_site_id, target_dump_site_id, statechart_state
                FROM vehicle_state_history
                WHERE simulation_id = ? AND vehicle_id = ?
                  AND received_at_ms BETWEEN ? AND ?
                ORDER BY received_at_ms DESC LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, simulationId);
            statement.setInt(2, vehicleId);
            statement.setLong(3, fromMs);
            statement.setLong(4, toMs);
            statement.setInt(5, limit);
            return rows(statement);
        }
    }

    public synchronized List<Map<String, Object>> shovelHistory(
            String simulationId, int shovelId, long fromMs, long toMs, int limit) throws SQLException {
        String sql = """
                SELECT simulation_id, shovel_id, sequence, sim_time_seconds, received_at_ms,
                       shovel_name, x, y, available, busy, waiting_trucks,
                       trucks_in_service, ore_grade, remaining_tons, produces_ore
                FROM shovel_state_history
                WHERE simulation_id = ? AND shovel_id = ?
                  AND received_at_ms BETWEEN ? AND ?
                ORDER BY received_at_ms DESC LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, simulationId);
            statement.setInt(2, shovelId);
            statement.setLong(3, fromMs);
            statement.setLong(4, toMs);
            statement.setInt(5, limit);
            return rows(statement);
        }
    }

    public synchronized List<Map<String, Object>> mapHistory(
            String simulationId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT simulation_id, sequence, sim_time_seconds, received_at_ms,
                       image_file, image_width_px, image_height_px, payload_json
                FROM map_state_history WHERE simulation_id = ?
                ORDER BY received_at_ms DESC LIMIT ?
                """)) {
            statement.setString(1, simulationId);
            statement.setInt(2, limit);
            return rows(statement);
        }
    }

    public synchronized Map<String, Long> counts() throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("mapHistory", count("map_state_history"));
        result.put("vehicleHistory", count("vehicle_state_history"));
        result.put("shovelHistory", count("shovel_state_history"));
        result.put("rejections", count("message_rejections"));
        result.put("decisionTraces", count("decision_trace"));
        result.put("users", count("app_user"));
        result.put("auditLogs", count("audit_log"));
        result.put("terminalActions", count("terminal_action"));
        result.put("alerts", count("operational_alert"));
        return result;
    }

    public synchronized boolean healthy() {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            return result.next() && result.getInt(1) == 1;
        } catch (SQLException exception) {
            return false;
        }
    }

    private long count(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private List<Map<String, Object>> rows(PreparedStatement statement) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    row.put(toCamel(metadata.getColumnLabel(column)), result.getObject(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private boolean migrationInstalled(String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void runMigration(String resource) throws IOException, SQLException {
        String sql = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                String trimmed = command.trim();
                if (!trimmed.isEmpty()) statement.execute(trimmed);
            }
            connection.commit();
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void createParentDirectory(String jdbcUrl) throws IOException {
        String prefix = "jdbc:sqlite:";
        if (!jdbcUrl.startsWith(prefix)) return;
        String value = jdbcUrl.substring(prefix.length());
        if (value.equals(":memory:") || value.startsWith("file:")) return;
        Path path = Path.of(value).toAbsolutePath();
        if (path.getParent() != null) Files.createDirectories(path.getParent());
    }

    private static String toCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(character));
                upper = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    @PreDestroy
    public synchronized void close() throws SQLException {
        if (connection != null) connection.close();
    }
}
