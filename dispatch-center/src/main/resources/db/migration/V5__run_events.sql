CREATE VIEW IF NOT EXISTS run_event_source AS
SELECT 'decision_trace:' || decision_id || ':created' AS event_id,
       'decision_trace' AS source, decision_id AS source_id,
       CASE WHEN decision_type='SPEED' THEN 'CONTROL_DECISION'
            WHEN decision_source='MANUAL_DISPATCH' THEN 'MANUAL_OVERRIDE'
            ELSE 'DISPATCH_DECISION' END AS event_type,
       simulation_id, created_at_ms AS wall_time_ms,
       json_extract(input_summary_json, '$.simTimeSeconds') AS sim_time_seconds,
       'VEHICLE' AS object_type, vehicle_id AS object_id, decision_id, command_id,
       validation_status AS result
FROM decision_trace
UNION ALL
SELECT 'decision_trace:' || decision_id || ':sent', 'decision_trace', decision_id,
       'COMMAND_SENT', simulation_id, published_at_ms, NULL,
       'VEHICLE', vehicle_id, decision_id, command_id, 'SENT'
FROM decision_trace WHERE published_at_ms IS NOT NULL
UNION ALL
SELECT 'decision_trace:' || decision_id || ':ack', 'decision_trace', decision_id,
       'COMMAND_ACK', simulation_id, ack_at_ms, NULL,
       'VEHICLE', vehicle_id, decision_id, command_id, ack_status
FROM decision_trace WHERE ack_at_ms IS NOT NULL
UNION ALL
SELECT 'vehicle_state_history:' || id, 'vehicle_state_history', CAST(id AS TEXT),
       'STATE_UPDATED', simulation_id, received_at_ms, sim_time_seconds,
       'VEHICLE', vehicle_id, NULL, NULL, 'RECORDED'
FROM vehicle_state_history
UNION ALL
SELECT 'shovel_state_history:' || id, 'shovel_state_history', CAST(id AS TEXT),
       'STATE_UPDATED', simulation_id, received_at_ms, sim_time_seconds,
       'SHOVEL', shovel_id, NULL, NULL, 'RECORDED'
FROM shovel_state_history
UNION ALL
SELECT 'map_state_history:' || id, 'map_state_history', CAST(id AS TEXT),
       'MAP_UPDATED', simulation_id, received_at_ms, sim_time_seconds,
       'MAP', NULL, NULL, NULL, 'RECORDED'
FROM map_state_history
UNION ALL
SELECT 'terminal_action:' || action_id, 'terminal_action', action_id,
       'TERMINAL_' || action_type, simulation_id, created_at_ms, NULL,
       object_type, object_id, NULL, command_id, result
FROM terminal_action
UNION ALL
SELECT 'operational_alert:' || alert_id || ':open', 'operational_alert', alert_id,
       'ALERT_OPENED', simulation_id, created_at_ms, NULL,
       object_type, object_id, NULL, NULL, 'OPEN'
FROM operational_alert
UNION ALL
SELECT 'operational_alert:' || alert_id || ':ack', 'operational_alert', alert_id,
       'ALERT_ACKNOWLEDGED', simulation_id, acknowledged_at_ms, NULL,
       object_type, object_id, NULL, NULL, 'ACKNOWLEDGED'
FROM operational_alert WHERE acknowledged_at_ms IS NOT NULL
UNION ALL
SELECT 'operational_alert:' || alert_id || ':process', 'operational_alert', alert_id,
       'ALERT_PROCESSING', simulation_id, processing_at_ms, NULL,
       object_type, object_id, NULL, NULL, 'PROCESSING'
FROM operational_alert WHERE processing_at_ms IS NOT NULL
UNION ALL
SELECT 'operational_alert:' || alert_id || ':close', 'operational_alert', alert_id,
       'ALERT_CLOSED', simulation_id, closed_at_ms, NULL,
       object_type, object_id, NULL, NULL, 'CLOSED'
FROM operational_alert WHERE closed_at_ms IS NOT NULL;
