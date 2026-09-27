package com.mine.dispatch.capability.decision;

import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.model.DecisionTrace;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("databaseRepository")
public class DecisionTraceRepository {
    private final String jdbcUrl;

    public DecisionTraceRepository(DispatchProperties properties) {
        this.jdbcUrl = properties.getStorage().getJdbcUrl();
    }

    public DecisionTrace insert(DecisionTrace trace) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO decision_trace(
                  decision_id, simulation_id, decision_type, decision_source, vehicle_id, shovel_id,
                  trigger_event, input_summary_json, model_output_json, explanation,
                  validation_status, validation_detail, command_id, command_topic,
                  created_at_ms, validated_at_ms, published_at_ms, ack_at_ms, ack_status, ack_detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, trace);
            statement.executeUpdate();
            return trace;
        }
    }

    public DecisionTrace markPublished(String commandId, long atMs) throws SQLException {
        return update(commandId, "published_at_ms = ?, ack_status = 'PENDING', ack_detail = '等待终端回执'",
                statement -> statement.setLong(1, atMs));
    }

    public DecisionTrace markPublishFailed(String commandId, String detail, long atMs) throws SQLException {
        return update(commandId, "ack_at_ms = ?, ack_status = 'PUBLISH_FAILED', ack_detail = ?", statement -> {
            statement.setLong(1, atMs);
            statement.setString(2, detail == null ? "" : detail);
        });
    }

    public Optional<DecisionTrace> acknowledge(String commandId, String status, String detail, long atMs)
            throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE decision_trace SET ack_at_ms = ?, ack_status = ?, ack_detail = ?
                WHERE command_id = ? AND ack_status IN ('CREATED', 'PENDING')
                """)) {
            statement.setLong(1, atMs);
            statement.setString(2, status);
            statement.setString(3, detail == null ? "" : detail);
            statement.setString(4, commandId);
            if (statement.executeUpdate() != 1) return Optional.empty();
        }
        return findByCommand(commandId);
    }

    public Optional<DecisionTrace> findByCommand(String commandId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM decision_trace WHERE command_id = ?")) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(row(result)) : Optional.empty();
            }
        }
    }

    public List<DecisionTrace> list(String simulationId, Integer vehicleId, Integer shovelId,
                                    String decisionType, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM decision_trace WHERE simulation_id = ?");
        if (vehicleId != null) sql.append(" AND vehicle_id = ?");
        if (shovelId != null) sql.append(" AND shovel_id = ?");
        if (decisionType != null && !decisionType.isBlank()) sql.append(" AND decision_type = ?");
        sql.append(" ORDER BY created_at_ms DESC LIMIT ?");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, simulationId);
            if (vehicleId != null) statement.setInt(index++, vehicleId);
            if (shovelId != null) statement.setInt(index++, shovelId);
            if (decisionType != null && !decisionType.isBlank()) statement.setString(index++, decisionType);
            statement.setInt(index, limit);
            List<DecisionTrace> traces = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) traces.add(row(result));
            }
            return traces;
        }
    }

    private DecisionTrace update(String commandId, String assignments, SqlBinder binder) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE decision_trace SET " + assignments + " WHERE command_id = ?")) {
            binder.bind(statement);
            statement.setString(statement.getParameterMetaData().getParameterCount(), commandId);
            if (statement.executeUpdate() != 1) throw new SQLException("decision not found: " + commandId);
        }
        return findByCommand(commandId).orElseThrow();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static void bind(PreparedStatement statement, DecisionTrace value) throws SQLException {
        int index = 1;
        statement.setString(index++, value.decisionId());
        statement.setString(index++, value.simulationId());
        statement.setString(index++, value.decisionType());
        statement.setString(index++, value.decisionSource());
        if (value.vehicleId() == null) statement.setNull(index++, Types.INTEGER); else statement.setInt(index++, value.vehicleId());
        if (value.shovelId() == null) statement.setNull(index++, Types.INTEGER); else statement.setInt(index++, value.shovelId());
        statement.setString(index++, value.triggerEvent());
        statement.setString(index++, value.inputSummaryJson());
        statement.setString(index++, value.modelOutputJson());
        if (value.explanation() == null) statement.setNull(index++, Types.VARCHAR); else statement.setString(index++, value.explanation());
        statement.setString(index++, value.validationStatus());
        statement.setString(index++, value.validationDetail());
        statement.setString(index++, value.commandId());
        statement.setString(index++, value.commandTopic());
        statement.setLong(index++, value.createdAtMs());
        statement.setLong(index++, value.validatedAtMs());
        if (value.publishedAtMs() == null) statement.setNull(index++, Types.BIGINT); else statement.setLong(index++, value.publishedAtMs());
        if (value.ackAtMs() == null) statement.setNull(index++, Types.BIGINT); else statement.setLong(index++, value.ackAtMs());
        statement.setString(index++, value.ackStatus());
        statement.setString(index, value.ackDetail());
    }

    private static DecisionTrace row(ResultSet result) throws SQLException {
        return new DecisionTrace(
                result.getString("decision_id"), result.getString("simulation_id"),
                result.getString("decision_type"), result.getString("decision_source"),
                nullableInt(result, "vehicle_id"), nullableInt(result, "shovel_id"),
                result.getString("trigger_event"), result.getString("input_summary_json"),
                result.getString("model_output_json"), result.getString("explanation"),
                result.getString("validation_status"), result.getString("validation_detail"),
                result.getString("command_id"), result.getString("command_topic"),
                result.getLong("created_at_ms"), result.getLong("validated_at_ms"),
                nullableLong(result, "published_at_ms"), nullableLong(result, "ack_at_ms"),
                result.getString("ack_status"), result.getString("ack_detail"));
    }

    private static Integer nullableInt(ResultSet result, String name) throws SQLException {
        int value = result.getInt(name); return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String name) throws SQLException {
        long value = result.getLong(name); return result.wasNull() ? null : value;
    }

    @FunctionalInterface
    private interface SqlBinder { void bind(PreparedStatement statement) throws SQLException; }
}
