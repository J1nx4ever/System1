CREATE TABLE IF NOT EXISTS production_event (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES run_context(run_id),
    simulation_id TEXT NOT NULL,
    cycle_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    site_id INTEGER NOT NULL,
    sim_time_seconds REAL NOT NULL,
    sent_wall_time_ms INTEGER NOT NULL,
    received_at_ms INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    UNIQUE(run_id,cycle_id,event_type)
);
CREATE INDEX IF NOT EXISTS idx_production_run_vehicle ON production_event(run_id,vehicle_id,sim_time_seconds);

CREATE VIEW IF NOT EXISTS run_event_projection AS
SELECT * FROM run_event_source
UNION ALL
SELECT 'production_event:' || event_id, 'production_event', event_id, event_type,
       simulation_id, sent_wall_time_ms, sim_time_seconds, 'VEHICLE', vehicle_id,
       NULL, json_extract(payload_json,'$.commandId'), 'RECORDED'
FROM production_event;

