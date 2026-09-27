CREATE TABLE IF NOT EXISTS manual_dispatch_command (
    command_id TEXT PRIMARY KEY,
    simulation_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    target_node TEXT NOT NULL,
    previous_destination_node TEXT NOT NULL,
    requested_at_ms INTEGER NOT NULL,
    published_at_ms INTEGER,
    completed_at_ms INTEGER,
    status TEXT NOT NULL,
    status_message TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_manual_dispatch_sim_time
    ON manual_dispatch_command(simulation_id, requested_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_manual_dispatch_vehicle_time
    ON manual_dispatch_command(simulation_id, vehicle_id, requested_at_ms DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_manual_dispatch_one_active_vehicle
    ON manual_dispatch_command(simulation_id, vehicle_id)
    WHERE status IN ('CREATED', 'SENT');
