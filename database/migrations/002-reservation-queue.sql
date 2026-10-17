-- BACK UP THE DATABASE AND STOP ALL OLD BACKEND INSTANCES BEFORE RUNNING.
-- Use the intended database first. This script never deletes business data.
-- MySQL DDL commits automatically; unresolved rows are reported and require manual repair.
CREATE TABLE IF NOT EXISTS migration_report (
  id bigint unsigned PRIMARY KEY AUTO_INCREMENT,
  migration varchar(80) NOT NULL,
  entity_id int unsigned NULL,
  issue varchar(255) NOT NULL,
  reported_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DELETE FROM migration_report WHERE migration='002-reservation-queue';
DELIMITER $$
DROP PROCEDURE IF EXISTS parking_add_column$$
CREATE PROCEDURE parking_add_column(IN table_name_arg varchar(64),IN column_name_arg varchar(64),IN definition_arg text)
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=table_name_arg AND column_name=column_name_arg) THEN
    SET @ddl=CONCAT('ALTER TABLE `',table_name_arg,'` ADD COLUMN `',column_name_arg,'` ',definition_arg);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DROP PROCEDURE IF EXISTS parking_upgrade$$
CREATE PROCEDURE parking_upgrade()
BEGIN
  DECLARE constraint_name_arg varchar(64);
  DECLARE done int DEFAULT 0;
  DECLARE checks_cursor CURSOR FOR SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='enter' AND constraint_type='CHECK';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;
  -- An older SQL-only schema used conflicting flag meanings. Never guess its data semantics.
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='enter' AND column_name='reservation') AND EXISTS(SELECT 1 FROM enter) THEN
    INSERT INTO migration_report(migration,entity_id,issue) SELECT '002-reservation-queue',id,'旧表缺少reservation字段，必须先人工确认flag含义并转换为0待入场/1在场/2离场' FROM enter;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Ambiguous legacy flag semantics; inspect migration_report before upgrading';
  END IF;
  CALL parking_add_column('enter','reservation','int NOT NULL DEFAULT 0');
  CALL parking_add_column('enter','space_id','int unsigned NULL');
  CALL parking_add_column('enter','reserved_at','datetime(3) NULL');
  CALL parking_add_column('enter','expires_at','datetime(3) NULL');
  CALL parking_add_column('enter','update_time','datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)');
  CALL parking_add_column('spaces','active_enter_id','int unsigned NULL');
  CALL parking_add_column('historicalSession','update_time','datetime NOT NULL DEFAULT CURRENT_TIMESTAMP');
  ALTER TABLE user MODIFY password varchar(100) NOT NULL, MODIFY plate_num varchar(10) NULL DEFAULT NULL, MODIFY tel_num varchar(11) NULL DEFAULT NULL;
  ALTER TABLE admin MODIFY password varchar(100) NOT NULL;
  -- Refuse silent financial rounding.
  IF EXISTS(SELECT 1 FROM user WHERE balance<>ROUND(balance,2)) OR EXISTS(SELECT 1 FROM rule WHERE unit_cost<>ROUND(unit_cost,2)) OR EXISTS(SELECT 1 FROM orders WHERE moneys<>ROUND(moneys,2)) OR EXISTS(SELECT 1 FROM recharges WHERE amounts<>ROUND(amounts,2)) THEN
    INSERT INTO migration_report(migration,issue) VALUES('002-reservation-queue','存在超过两位小数的金额，请核对并处理后重跑');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Financial precision needs manual review';
  END IF;
  ALTER TABLE user MODIFY balance decimal(14,2) NOT NULL DEFAULT 0;
  ALTER TABLE rule MODIFY unit_cost decimal(12,2) NOT NULL;
  ALTER TABLE orders MODIFY moneys decimal(14,2) NOT NULL;
  ALTER TABLE recharges MODIFY amounts decimal(14,2) NOT NULL;
  UPDATE user SET plate_num=NULL WHERE plate_num='';
  UPDATE user SET tel_num=NULL WHERE tel_num='';
  OPEN checks_cursor;
  drop_checks: LOOP
    FETCH checks_cursor INTO constraint_name_arg;
    IF done=1 THEN LEAVE drop_checks; END IF;
    SET @ddl=CONCAT('ALTER TABLE enter DROP CHECK `',constraint_name_arg,'`');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE checks_cursor;
  ALTER TABLE enter ADD CONSTRAINT chk_enter_flag CHECK(flag in (0,1,2)), ADD CONSTRAINT chk_enter_reservation CHECK(reservation in (0,1,2));
  -- Link only an unambiguous (user, parking, occupied space) match.
  UPDATE enter e
  JOIN user u ON u.id=e.user_id
  JOIN (SELECT park_id,user_name,MIN(id) AS space_id FROM spaces WHERE spaces_flag=1 GROUP BY park_id,user_name HAVING COUNT(*)=1) s ON s.park_id=e.park_id AND s.user_name=u.user_name
  JOIN (SELECT user_id,COUNT(*) AS active_count FROM enter WHERE flag=1 OR(flag=0 AND reservation=1) GROUP BY user_id HAVING COUNT(*)=1) a ON a.user_id=e.user_id
  SET e.space_id=s.space_id
  WHERE e.space_id IS NULL AND (e.flag=1 OR(e.flag=0 AND e.reservation=1));
  UPDATE enter SET reserved_at=COALESCE(reserved_at,enter_time),expires_at=COALESCE(expires_at,DATE_ADD(enter_time,INTERVAL 30 MINUTE)) WHERE flag=0 AND reservation=1;
  INSERT INTO migration_report(migration,entity_id,issue)
    SELECT '002-reservation-queue',id,'有效记录无法唯一关联车位，禁止启动新服务直至核对完成' FROM enter WHERE (flag=1 OR(flag=0 AND reservation=1)) AND space_id IS NULL;
  INSERT INTO migration_report(migration,entity_id,issue)
    SELECT '002-reservation-queue',MIN(id),'同一用户存在多条有效记录' FROM enter WHERE flag=1 OR(flag=0 AND reservation=1) GROUP BY user_id HAVING COUNT(*)>1;
  INSERT INTO migration_report(migration,entity_id,issue)
    SELECT '002-reservation-queue',MIN(id),'同一车位存在多条有效记录' FROM enter WHERE space_id IS NOT NULL AND (flag=1 OR(flag=0 AND reservation=1)) GROUP BY space_id HAVING COUNT(*)>1;
  INSERT INTO migration_report(migration,entity_id,issue)
    SELECT '002-reservation-queue',s.id,'占用车位无唯一有效记录，请人工核对' FROM spaces s LEFT JOIN enter e ON e.space_id=s.id AND (e.flag=1 OR(e.flag=0 AND e.reservation=1)) WHERE s.spaces_flag=1 GROUP BY s.id HAVING COUNT(e.id)<>1;
  IF EXISTS(SELECT 1 FROM migration_report WHERE migration='002-reservation-queue') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unresolved data; inspect migration_report, repair explicitly, then rerun';
  END IF;
  UPDATE spaces s JOIN enter e ON e.space_id=s.id AND (e.flag=1 OR(e.flag=0 AND e.reservation=1)) SET s.active_enter_id=e.id;
  CALL parking_add_column('enter','active_user_id','int unsigned GENERATED ALWAYS AS (CASE WHEN flag=1 OR(flag=0 AND reservation=1) THEN user_id ELSE NULL END) STORED');
  CALL parking_add_column('enter','active_space_id','int unsigned GENERATED ALWAYS AS (CASE WHEN flag=1 OR(flag=0 AND reservation=1) THEN space_id ELSE NULL END) STORED');
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='enter' AND index_name='uk_enter_active_user') THEN CREATE UNIQUE INDEX uk_enter_active_user ON enter(active_user_id); END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='enter' AND index_name='uk_enter_active_space') THEN CREATE UNIQUE INDEX uk_enter_active_space ON enter(active_space_id); END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='enter' AND index_name='idx_enter_pending') THEN CREATE INDEX idx_enter_pending ON enter(flag,reservation,expires_at); END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='spaces' AND index_name='uk_space_entry') THEN CREATE UNIQUE INDEX uk_space_entry ON spaces(active_enter_id); END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='spaces' AND index_name='uk_space_coordinates') THEN CREATE UNIQUE INDEX uk_space_coordinates ON spaces(park_id,spaces_col,spaces_row,spaces_storey); END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='recharges' AND index_name='uk_recharges_provider_trade') THEN CREATE UNIQUE INDEX uk_recharges_provider_trade ON recharges((NULLIF(pay_no,''))); END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='historicalSession' AND index_name='uk_chat_id') AND NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='historicalSession' AND column_name='chatId' AND non_unique=0) THEN CREATE UNIQUE INDEX uk_chat_id ON historicalSession(chatId); END IF;
  CREATE TABLE IF NOT EXISTS reservation_event (
    reservation_id int unsigned PRIMARY KEY,expires_at datetime(3) NOT NULL,
    delivered_at datetime(3) NULL,completed_at datetime(3) NULL,
    attempts int NOT NULL DEFAULT 0,last_error varchar(255) NULL,
    KEY idx_reservation_event_pending(completed_at),FOREIGN KEY(reservation_id) REFERENCES enter(id)
  );
  INSERT INTO reservation_event(reservation_id,expires_at) SELECT id,expires_at FROM enter WHERE flag=0 AND reservation=1 ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at);
  UPDATE parking p SET park_mount=(SELECT COUNT(*) FROM spaces WHERE park_id=p.id AND spaces_flag IN(0,1)),park_spare=(SELECT COUNT(*) FROM spaces WHERE park_id=p.id AND spaces_flag=0);
END$$
CALL parking_upgrade()$$
DROP PROCEDURE parking_upgrade$$
DROP PROCEDURE parking_add_column$$
DELIMITER ;
SELECT * FROM migration_report WHERE migration='002-reservation-queue';
