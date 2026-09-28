CREATE TABLE IF NOT EXISTS terminal_message (
    message_id TEXT PRIMARY KEY,
    simulation_id TEXT NOT NULL,
    object_type TEXT NOT NULL CHECK (object_type IN ('VEHICLE','SHOVEL')),
    object_id INTEGER NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('OUTBOUND','INBOUND')),
    sender_role TEXT NOT NULL CHECK (sender_role IN ('DISPATCHER','VEHICLE','SHOVEL')),
    sender_name TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING','SENT','DELIVERED','RECEIVED','FAILED')),
    reply_to_message_id TEXT,
    status_detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_terminal_message_target_time
    ON terminal_message(simulation_id, object_type, object_id, created_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_terminal_message_reply
    ON terminal_message(reply_to_message_id);
