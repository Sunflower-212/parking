CREATE TABLE IF NOT EXISTS background_event (
  id char(36) PRIMARY KEY,
  actor_id int unsigned NOT NULL,
  kind varchar(20) NOT NULL,
  request_key varchar(64) NOT NULL,
  payload json NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'READY',
  attempts int NOT NULL DEFAULT 0,
  available_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_token char(36) NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at datetime(3) NULL,
  expires_at datetime(3) NULL,
  error_code varchar(80) NULL,
  result_file varchar(100) NULL,
  UNIQUE KEY uk_background_request(actor_id,kind,request_key),
  KEY idx_background_due(status,available_at,id),
  KEY idx_background_expiry(kind,expires_at,id)
);
CREATE TABLE IF NOT EXISTS admin_audit (
  event_id char(36) PRIMARY KEY,
  actor_id int unsigned NOT NULL,
  action varchar(50) NOT NULL,
  entity_ids json NOT NULL,
  occurred_at datetime(3) NOT NULL,
  KEY idx_audit_actor(actor_id,occurred_at,event_id)
);
