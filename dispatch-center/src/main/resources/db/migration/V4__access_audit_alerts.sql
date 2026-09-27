CREATE TABLE IF NOT EXISTS app_user (
    user_id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('DISPATCHER', 'VEHICLE', 'SHOVEL')),
    simulation_id TEXT,
    subject_id INTEGER,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at_ms INTEGER NOT NULL,
    CHECK ((role = 'DISPATCHER' AND simulation_id IS NULL AND subject_id IS NULL)
        OR (role IN ('VEHICLE', 'SHOVEL') AND simulation_id IS NOT NULL AND subject_id IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS auth_session (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES app_user(user_id),
    created_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    last_seen_at_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_session_expiry ON auth_session(expires_at_ms);
CREATE TABLE IF NOT EXISTS audit_log (
    audit_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    actor_user_id TEXT,
    actor_username TEXT NOT NULL,
    actor_role TEXT NOT NULL,
    operation TEXT NOT NULL,
    object_type TEXT NOT NULL,
    object_id TEXT NOT NULL,
    occurred_at_ms INTEGER NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
    detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(occurred_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit_log(actor_username, occurred_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_audit_object_time ON audit_log(object_type, object_id, occurred_at_ms DESC);
CREATE TABLE IF NOT EXISTS terminal_action (
    action_id TEXT PRIMARY KEY,
    simulation_id TEXT NOT NULL,
    actor_user_id TEXT NOT NULL REFERENCES app_user(user_id),
    actor_username TEXT NOT NULL,
    actor_role TEXT NOT NULL CHECK (actor_role IN ('VEHICLE', 'SHOVEL')),
    object_type TEXT NOT NULL CHECK (object_type IN ('VEHICLE', 'SHOVEL')),
    object_id INTEGER NOT NULL,
    action_type TEXT NOT NULL CHECK (action_type IN ('REQUEST', 'EXCEPTION', 'TASK_CONFIRMATION')),
    command_id TEXT,
    detail TEXT NOT NULL DEFAULT '',
    created_at_ms INTEGER NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('ACCEPTED', 'REJECTED')),
    result_detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_terminal_action_object_time ON terminal_action(simulation_id, object_type, object_id, created_at_ms DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_terminal_task_confirmation_once ON terminal_action(actor_user_id, command_id) WHERE action_type = 'TASK_CONFIRMATION' AND result = 'ACCEPTED';
CREATE TABLE IF NOT EXISTS operational_alert (
    alert_id TEXT PRIMARY KEY,
    simulation_id TEXT NOT NULL,
    source_action_id TEXT REFERENCES terminal_action(action_id),
    object_type TEXT NOT NULL CHECK (object_type IN ('VEHICLE', 'SHOVEL')),
    object_id INTEGER NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    code TEXT NOT NULL,
    message TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'PROCESSING', 'CLOSED')),
    created_at_ms INTEGER NOT NULL,
    acknowledged_by TEXT,
    acknowledged_at_ms INTEGER,
    processing_by TEXT,
    processing_at_ms INTEGER,
    closed_by TEXT,
    closed_at_ms INTEGER,
    resolution TEXT NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_alert_sim_status_time ON operational_alert(simulation_id, status, created_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_alert_object_time ON operational_alert(simulation_id, object_type, object_id, created_at_ms DESC);
