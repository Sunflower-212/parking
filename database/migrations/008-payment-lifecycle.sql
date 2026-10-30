-- Additive and repeatable. Stop the old backend before applying this migration.
USE parking_springboot;
DROP PROCEDURE IF EXISTS parking_payment_upgrade;
DELIMITER $$
CREATE PROCEDURE parking_payment_upgrade()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='recharges' AND column_name='merchant_order_no') THEN
    ALTER TABLE recharges ADD merchant_order_no varchar(64) NULL,
      ADD expires_at datetime(3) NULL, ADD payment_state varchar(16) NOT NULL DEFAULT 'VERIFYING',
      ADD cancel_requested boolean NOT NULL DEFAULT false;
  END IF;
  UPDATE recharges SET merchant_order_no=CAST(id AS CHAR) WHERE merchant_order_no IS NULL;
  UPDATE recharges SET payment_state='PAID' WHERE flag=1 AND payment_state<>'PAID';
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='recharges' AND index_name='uk_recharge_merchant_order') THEN
    ALTER TABLE recharges ADD UNIQUE INDEX uk_recharge_merchant_order(merchant_order_no);
  END IF;
END$$
DELIMITER ;
CALL parking_payment_upgrade();
DROP PROCEDURE parking_payment_upgrade;
CREATE TABLE IF NOT EXISTS payment_event (
  recharge_id int unsigned PRIMARY KEY,
  version bigint NOT NULL DEFAULT 1,
  next_run_at datetime(3) NOT NULL,
  last_checked_at datetime(3) NULL,
  lease_token varchar(36) NULL,
  lease_until datetime(3) NULL,
  attempts int NOT NULL DEFAULT 0,
  delivered_at datetime(3) NULL,
  completed_at datetime(3) NULL,
  last_error varchar(64) NULL,
  request_id varchar(64) NULL,
  KEY idx_payment_event_pending(completed_at,recharge_id),
  FOREIGN KEY(recharge_id) REFERENCES recharges(id) ON DELETE CASCADE
) ENGINE=InnoDB;
