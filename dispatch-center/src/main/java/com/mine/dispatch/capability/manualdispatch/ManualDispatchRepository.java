package com.mine.dispatch.capability.manualdispatch;

import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.model.ManualDispatchCommand;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("databaseRepository")
public class ManualDispatchRepository {
    private final String jdbcUrl;

    public ManualDispatchRepository(DispatchProperties properties) {
        this.jdbcUrl = properties.getStorage().getJdbcUrl();
    }

    public ManualDispatchCommand insert(ManualDispatchCommand command) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO manual_dispatch_command(
                       command_id, simulation_id, vehicle_id, target_node,
                       previous_destination_node, requested_at_ms, status, status_message
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, command.commandId());
            statement.setString(2, command.simulationId());
            statement.setInt(3, command.vehicleId());
            statement.setString(4, command.targetNode());
            statement.setString(5, command.previousDestinationNode());
            statement.setLong(6, command.requestedAtMs());
            statement.setString(7, command.status());
            statement.setString(8, command.statusMessage());
            statement.executeUpdate();
            return command;
        }
    }

    public Optional<ManualDispatchCommand> find(String commandId) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM manual_dispatch_command WHERE command_id = ?")) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(row(result)) : Optional.empty();
            }
        }
    }

    public List<ManualDispatchCommand> list(String simulationId, Integer vehicleId, int limit)
            throws SQLException {
        String sql = "SELECT * FROM manual_dispatch_command WHERE simulation_id = ?" +
                (vehicleId == null ? "" : " AND vehicle_id = ?") +
                " ORDER BY requested_at_ms DESC LIMIT ?";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, simulationId);
            if (vehicleId != null) statement.setInt(index++, vehicleId);
            statement.setInt(index, limit);
            List<ManualDispatchCommand> commands = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) commands.add(row(result));
            }
            return commands;
        }
    }

    public int expireStaleActive(String simulationId, Integer vehicleId,
                                 long requestedBeforeMs, long completedAtMs) throws SQLException {
        String sql = """
                UPDATE manual_dispatch_command
                SET status = 'EXPIRED',
                    status_message = 'No terminal acknowledgement before timeout',
                    completed_at_ms = ?
                WHERE simulation_id = ?
                  AND requested_at_ms < ?
                  AND status IN ('CREATED', 'SENT')
                """ + (vehicleId == null ? "" : " AND vehicle_id = ?");
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, completedAtMs);
            statement.setString(index++, simulationId);
            statement.setLong(index++, requestedBeforeMs);
            if (vehicleId != null) statement.setInt(index, vehicleId);
            return statement.executeUpdate();
        }
    }

    public ManualDispatchCommand markSent(String commandId, long publishedAtMs) throws SQLException {
        return update(commandId, "SENT", "MQTT command published", publishedAtMs, null);
    }

    public ManualDispatchCommand markPublishFailed(String commandId, String message) throws SQLException {
        return update(commandId, "PUBLISH_FAILED", message, null, System.currentTimeMillis());
    }

    public Optional<ManualDispatchCommand> complete(String commandId, String simulationId,
                                                     int vehicleId, String targetNode,
                                                     String status, String message,
                                                     long completedAtMs) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE manual_dispatch_command
                     SET status = ?, status_message = ?, completed_at_ms = ?
                     WHERE command_id = ? AND simulation_id = ? AND vehicle_id = ?
                       AND target_node = ? AND status IN ('CREATED', 'SENT')
                     """)) {
            statement.setString(1, status);
            statement.setString(2, message == null ? "" : message);
            statement.setLong(3, completedAtMs);
            statement.setString(4, commandId);
            statement.setString(5, simulationId);
            statement.setInt(6, vehicleId);
            statement.setString(7, targetNode);
            if (statement.executeUpdate() != 1) return Optional.empty();
        }
        return find(commandId);
    }

    private ManualDispatchCommand update(String commandId, String status, String message,
                                          Long publishedAtMs, Long completedAtMs) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE manual_dispatch_command
                     SET status = ?, status_message = ?,
                         published_at_ms = COALESCE(?, published_at_ms),
                         completed_at_ms = COALESCE(?, completed_at_ms)
                     WHERE command_id = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, message == null ? "" : message);
            if (publishedAtMs == null) statement.setNull(3, Types.BIGINT); else statement.setLong(3, publishedAtMs);
            if (completedAtMs == null) statement.setNull(4, Types.BIGINT); else statement.setLong(4, completedAtMs);
            statement.setString(5, commandId);
            if (statement.executeUpdate() != 1) throw new SQLException("command not found: " + commandId);
        }
        return find(commandId).orElseThrow();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static ManualDispatchCommand row(ResultSet result) throws SQLException {
        return new ManualDispatchCommand(
                result.getString("command_id"), result.getString("simulation_id"),
                result.getInt("vehicle_id"), result.getString("target_node"),
                result.getString("previous_destination_node"), result.getLong("requested_at_ms"),
                nullableLong(result, "published_at_ms"), nullableLong(result, "completed_at_ms"),
                result.getString("status"), result.getString("status_message"));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
