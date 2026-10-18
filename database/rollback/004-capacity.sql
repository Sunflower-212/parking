-- Stop ALL writers, back up, and verify the reconciliation SELECT is empty before running.
-- Keep the capacity table for inspection; old binaries only read legacy columns.
DELIMITER $$
DROP PROCEDURE IF EXISTS rollback_capacity$$
CREATE PROCEDURE rollback_capacity()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  IF EXISTS(SELECT 1 FROM parking p LEFT JOIN parking_capacity_total c ON c.park_id=p.id
      LEFT JOIN (SELECT park_id,SUM(spaces_flag IN (0,1)) total,SUM(spaces_flag=0) available FROM spaces GROUP BY park_id) s ON s.park_id=p.id
      WHERE c.park_id IS NULL OR c.park_mount<>COALESCE(s.total,0) OR c.park_spare<>COALESCE(s.available,0)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Capacity mismatch: rollback stopped before changing legacy counters';
  END IF;
  START TRANSACTION;
  UPDATE parking p JOIN parking_capacity_total c ON c.park_id=p.id
  SET p.park_mount=c.park_mount,p.park_spare=c.park_spare;
  -- Prevent a new writer from starting against counters frozen by a legacy writer.
  UPDATE capacity_settings SET buckets=0 WHERE id=1;
  COMMIT;
END$$
CALL rollback_capacity()$$
DROP PROCEDURE rollback_capacity$$
DELIMITER ;
SELECT p.id FROM parking p JOIN parking_capacity_total c ON c.park_id=p.id
WHERE p.park_mount<>c.park_mount OR p.park_spare<>c.park_spare;
