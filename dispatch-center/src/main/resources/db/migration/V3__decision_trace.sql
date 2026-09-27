CREATE TABLE IF NOT EXISTS decision_trace (
    decision_id TEXT PRIMARY KEY,
    simulation_id TEXT NOT NULL,
    decision_type TEXT NOT NULL,
    decision_source TEXT NOT NULL,
    vehicle_id INTEGER,
    shovel_id INTEGER,
    trigger_event TEXT NOT NULL,
    input_summary_json TEXT NOT NULL,
    model_output_json TEXT NOT NULL,
    explanation TEXT,
    validation_status TEXT NOT NULL,
    validation_detail TEXT NOT NULL,
    command_id TEXT NOT NULL UNIQUE,
    command_topic TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    validated_at_ms INTEGER NOT NULL,
    published_at_ms INTEGER,
    ack_at_ms INTEGER,
    ack_status TEXT NOT NULL,
    ack_detail TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_decision_trace_sim_time
    ON decision_trace(simulation_id, created_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_decision_trace_vehicle_time
    ON decision_trace(simulation_id, vehicle_id, created_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_decision_trace_shovel_time
    ON decision_trace(simulation_id, shovel_id, created_at_ms DESC);
