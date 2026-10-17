-- Back up the existing database before running. No business records are changed.
USE parking_springboot;
DROP PROCEDURE IF EXISTS parking_add_index;
DELIMITER $$
CREATE PROCEDURE parking_add_index(IN tab VARCHAR(64), IN idx VARCHAR(64), IN cols VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=tab AND index_name=idx)
     AND NOT EXISTS (
       SELECT 1 FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name=tab
       GROUP BY index_name
       HAVING LOWER(GROUP_CONCAT(CONCAT(column_name,IF(collation='D',' DESC','')) ORDER BY seq_in_index SEPARATOR ','))=LOWER(cols)
     ) THEN
    SET @ddl=CONCAT('ALTER TABLE `',tab,'` ADD INDEX `',idx,'` (',cols,')');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;
CALL parking_add_index('orders','idx_orders_user_time','user_id,create_time DESC,id DESC');
CALL parking_add_index('orders','idx_orders_park_time','park_id,create_time DESC,id DESC');
CALL parking_add_index('orders','idx_orders_time','create_time DESC,id DESC');
CALL parking_add_index('enter','idx_enter_user_time','user_id,enter_time DESC,id DESC');
CALL parking_add_index('enter','idx_enter_park_time','park_id,enter_time DESC,id DESC');
CALL parking_add_index('enter','idx_enter_time_flag','enter_time,flag');
CALL parking_add_index('spaces','idx_spaces_available','park_id,spaces_flag,active_enter_id,id');
CALL parking_add_index('recharges','idx_recharges_user_time','user_id,create_time DESC,id DESC');
CALL parking_add_index('recharges','idx_recharges_flag_time','flag,create_time,id');
CALL parking_add_index('recharges','idx_recharges_time','create_time DESC,id DESC');
CALL parking_add_index('user','idx_user_created','create_time,id');
CALL parking_add_index('user','idx_user_vip_expiry','status,vip_end_time,id');
CALL parking_add_index('historicalSession','idx_session_user_type_time','userId,type,update_time DESC,id DESC');
DROP PROCEDURE parking_add_index;

-- Reuse the coordinate uniqueness index for floor reads instead of retaining a duplicate.
DROP PROCEDURE IF EXISTS parking_normalize_indexes;
DELIMITER $$
CREATE PROCEDURE parking_normalize_indexes()
BEGIN
  IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='spaces' AND index_name='uk_space_coordinates') <> 'park_id,spaces_storey,spaces_row,spaces_col' THEN
    ALTER TABLE spaces DROP INDEX uk_space_coordinates, ADD UNIQUE INDEX uk_space_coordinates(park_id,spaces_storey,spaces_row,spaces_col);
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='spaces' AND index_name='idx_spaces_floor') THEN
    ALTER TABLE spaces DROP INDEX idx_spaces_floor;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='enter' AND index_name='fk_enter_user') THEN
    ALTER TABLE enter DROP INDEX fk_enter_user;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='enter' AND index_name='fk_enter_park') THEN
    ALTER TABLE enter DROP INDEX fk_enter_park;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='recharges' AND index_name='fk_recharges_user') THEN
    ALTER TABLE recharges DROP INDEX fk_recharges_user;
  END IF;
END$$
DELIMITER ;
CALL parking_normalize_indexes();
DROP PROCEDURE parking_normalize_indexes;
