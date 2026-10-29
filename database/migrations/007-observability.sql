-- Additive migration. Back up first; old audit events retain null for unrecorded fields.
USE parking_springboot;
DROP PROCEDURE IF EXISTS parking_observability_upgrade;
DELIMITER $$
CREATE PROCEDURE parking_observability_upgrade()
BEGIN
  ALTER TABLE admin_audit MODIFY actor_id int unsigned NULL;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin_audit' AND column_name='actor_role') THEN
    ALTER TABLE admin_audit ADD actor_role int NULL, ADD result varchar(16) NULL, ADD reason_code varchar(80) NULL, ADD request_id varchar(64) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='background_event' AND column_name='request_id') THEN
    ALTER TABLE background_event ADD request_id varchar(64) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='reservation_event' AND column_name='request_id') THEN
    ALTER TABLE reservation_event ADD request_id varchar(64) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='admin_audit' AND index_name='idx_audit_time') THEN
    ALTER TABLE admin_audit ADD INDEX idx_audit_time(occurred_at,event_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='admin_audit' AND index_name='idx_audit_action_time') THEN
    ALTER TABLE admin_audit ADD INDEX idx_audit_action_time(action,occurred_at,event_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='admin_audit' AND index_name='idx_audit_request') THEN
    ALTER TABLE admin_audit ADD INDEX idx_audit_request(request_id);
  END IF;
END$$
DELIMITER ;
CALL parking_observability_upgrade();
DROP PROCEDURE parking_observability_upgrade;
