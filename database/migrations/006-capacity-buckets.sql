-- All writers MUST be stopped. Default is sixteen buckets; set @capacity_buckets=1 explicitly for controlled comparison.
SET @capacity_buckets=COALESCE(@capacity_buckets,16);
DELIMITER $$
DROP PROCEDURE IF EXISTS capacity_buckets_migrate$$
CREATE PROCEDURE capacity_buckets_migrate()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  IF @capacity_buckets NOT IN (1,16) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Supported capacity buckets: 1 or 16'; END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='parking_capacity' AND column_name='bucket_id') THEN
    ALTER TABLE parking_capacity ADD COLUMN bucket_id smallint unsigned NOT NULL DEFAULT 0, DROP PRIMARY KEY, ADD PRIMARY KEY(park_id,bucket_id);
  END IF;
  CREATE TABLE IF NOT EXISTS capacity_settings(id int PRIMARY KEY,buckets int NOT NULL);
  -- 暂停全部写入后，先核对旧容量合计与真实车位状态；差异未解释时立即中止，不直接覆盖。
  IF EXISTS(
    SELECT 1 FROM parking p
    LEFT JOIN (SELECT park_id,SUM(park_mount) total,SUM(park_spare) available FROM parking_capacity GROUP BY park_id) c ON c.park_id=p.id
    LEFT JOIN (SELECT park_id,SUM(spaces_flag IN (0,1)) total,SUM(spaces_flag=0) available FROM spaces GROUP BY park_id) s ON s.park_id=p.id
    WHERE c.park_id IS NULL OR c.total<>COALESCE(s.total,0) OR c.available<>COALESCE(s.available,0)
  ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Capacity mismatch: bucket migration stopped'; END IF;
  -- 重建所有停车场的完整桶集合，并在同一事务记录桶数；Java 必须采用相同的十进制 ID CRC32 映射。
  START TRANSACTION;
  DELETE FROM parking_capacity;
  INSERT INTO parking_capacity(park_id,bucket_id,park_mount,park_spare)
    SELECT p.id,b.n,COALESCE(SUM(s.spaces_flag IN (0,1)),0),COALESCE(SUM(s.spaces_flag=0),0)
    FROM parking p CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15) b
    LEFT JOIN spaces s ON s.park_id=p.id AND MOD(CRC32(CAST(s.id AS CHAR)),@capacity_buckets)=b.n
    WHERE b.n<@capacity_buckets GROUP BY p.id,b.n;
  INSERT INTO capacity_settings VALUES(1,@capacity_buckets) ON DUPLICATE KEY UPDATE buckets=VALUES(buckets);
  COMMIT;
END$$
CALL capacity_buckets_migrate()$$
DROP PROCEDURE capacity_buckets_migrate$$
DELIMITER ;
-- 兼容接口仍返回总容量与余位，统一在数据库汇总桶计数，禁止继续双写旧 parking 计数字段。
CREATE OR REPLACE VIEW parking_capacity_total AS SELECT park_id,SUM(park_mount) park_mount,SUM(park_spare) park_spare FROM parking_capacity GROUP BY park_id;
