-- Fresh installations use sixteen fixed hash buckets; incremental migration can explicitly select one.
ALTER TABLE parking_capacity ADD COLUMN bucket_id smallint unsigned NOT NULL DEFAULT 0, DROP PRIMARY KEY, ADD PRIMARY KEY(park_id,bucket_id);
CREATE TABLE capacity_settings(id int PRIMARY KEY,buckets int NOT NULL);
INSERT INTO capacity_settings VALUES(1,16);
CREATE VIEW parking_capacity_total AS SELECT park_id,SUM(park_mount) park_mount,SUM(park_spare) park_spare FROM parking_capacity GROUP BY park_id;
