CREATE TABLE IF NOT EXISTS run_context (
    run_id TEXT PRIMARY KEY,
    simulation_id TEXT NOT NULL,
    scenario_id TEXT,
    started_at_ms INTEGER NOT NULL,
    ended_at_ms INTEGER,
    simulation_start_seconds REAL,
    simulation_end_seconds REAL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_by TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_run_one_active_instance
    ON run_context(simulation_id) WHERE ended_at_ms IS NULL;
