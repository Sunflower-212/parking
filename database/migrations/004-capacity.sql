-- Stop ALL writers and take a backup before executing. Do not run old/new backends together.
CREATE TABLE IF NOT EXISTS parking_capacity (
  park_id int unsigned PRIMARY KEY,
  park_mount int NOT NULL,
  park_spare int NOT NULL,
  CONSTRAINT fk_capacity_parking FOREIGN KEY (park_id) REFERENCES parking(id) ON DELETE CASCADE,
  CONSTRAINT ck_capacity CHECK (park_mount>=0 AND park_spare>=0 AND park_spare<=park_mount)
);
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_capacity$$
CREATE PROCEDURE migrate_capacity()
BEGIN
  DECLARE legacy_mode bool DEFAULT false;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='capacity_settings') THEN
    SELECT COALESCE(MAX(buckets=0),0) INTO legacy_mode FROM capacity_settings WHERE id=1;
  END IF;
  -- Rollback marks buckets=0. Legacy writers then update only legacy counters.
  -- Rebuild derived rows only after independently verifying those counters against spaces.
  IF legacy_mode THEN
    IF EXISTS(SELECT 1 FROM parking p LEFT JOIN
      (SELECT park_id,SUM(spaces_flag IN(0,1)) total,SUM(spaces_flag=0) available FROM spaces GROUP BY park_id) s ON s.park_id=p.id
      WHERE p.park_mount<>COALESCE(s.total,0) OR p.park_spare<>COALESCE(s.available,0)) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Legacy rollback counters mismatch: re-upgrade stopped';
    END IF;
    DELETE FROM parking_capacity;
  END IF;
  -- Existing new counters are checked on rerun; frozen legacy fields are checked only on first migration.
  IF EXISTS (
    SELECT 1 FROM parking p LEFT JOIN (SELECT park_id,SUM(park_mount) park_mount,SUM(park_spare) park_spare FROM parking_capacity GROUP BY park_id) c ON c.park_id=p.id
    LEFT JOIN (SELECT park_id,SUM(spaces_flag IN (0,1)) total,SUM(spaces_flag=0) available FROM spaces GROUP BY park_id) s ON s.park_id=p.id
    WHERE COALESCE(c.park_mount,p.park_mount)<>COALESCE(s.total,0)
       OR COALESCE(c.park_spare,p.park_spare)<>COALESCE(s.available,0)
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Capacity mismatch: reconcile spaces and counters before migration';
  END IF;
  INSERT INTO parking_capacity(park_id,park_mount,park_spare)
    SELECT p.id,p.park_mount,p.park_spare FROM parking p LEFT JOIN parking_capacity c ON c.park_id=p.id WHERE c.park_id IS NULL;
END$$
CALL migrate_capacity()$$
DROP PROCEDURE migrate_capacity$$
DELIMITER ;
