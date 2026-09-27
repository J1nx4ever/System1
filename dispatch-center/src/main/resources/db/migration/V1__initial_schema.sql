CREATE TABLE IF NOT EXISTS map_state_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    simulation_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    sim_time_seconds REAL NOT NULL,
    received_at_ms INTEGER NOT NULL,
    image_file TEXT NOT NULL,
    image_width_px INTEGER NOT NULL,
    image_height_px INTEGER NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_map_history_sim_time
    ON map_state_history(simulation_id, received_at_ms DESC);

CREATE TABLE IF NOT EXISTS vehicle_state_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    simulation_id TEXT NOT NULL,
    vehicle_id INTEGER NOT NULL,
    sequence INTEGER NOT NULL,
    sim_time_seconds REAL NOT NULL,
    sent_wall_time_ms INTEGER NOT NULL,
    received_at_ms INTEGER NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    speed_mps REAL NOT NULL,
    loaded INTEGER NOT NULL,
    payload_tons REAL NOT NULL,
    current_node TEXT NOT NULL,
    destination_node TEXT NOT NULL,
    current_segment_id TEXT NOT NULL,
    target_load_site_id INTEGER NOT NULL,
    target_dump_site_id INTEGER NOT NULL,
    statechart_state TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vehicle_history_entity_time
    ON vehicle_state_history(simulation_id, vehicle_id, received_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_vehicle_history_sim_sequence
    ON vehicle_state_history(simulation_id, sequence DESC);

CREATE TABLE IF NOT EXISTS shovel_state_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    simulation_id TEXT NOT NULL,
    shovel_id INTEGER NOT NULL,
    sequence INTEGER NOT NULL,
    sim_time_seconds REAL NOT NULL,
    received_at_ms INTEGER NOT NULL,
    shovel_name TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    available INTEGER NOT NULL,
    busy INTEGER NOT NULL,
    waiting_trucks INTEGER NOT NULL,
    trucks_in_service INTEGER NOT NULL,
    ore_grade REAL NOT NULL,
    remaining_tons REAL NOT NULL,
    produces_ore INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_shovel_history_entity_time
    ON shovel_state_history(simulation_id, shovel_id, received_at_ms DESC);

CREATE TABLE IF NOT EXISTS message_rejections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    topic TEXT NOT NULL,
    received_at_ms INTEGER NOT NULL,
    reason TEXT NOT NULL,
    payload_size INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rejections_time
    ON message_rejections(received_at_ms DESC);
