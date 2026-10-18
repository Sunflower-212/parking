-- Vehicle transactions update capacity independently of parking metadata.
CREATE TABLE IF NOT EXISTS parking_capacity (
  park_id int unsigned PRIMARY KEY,
  park_mount int NOT NULL,
  park_spare int NOT NULL,
  CONSTRAINT fk_capacity_parking FOREIGN KEY (park_id) REFERENCES parking(id) ON DELETE CASCADE,
  CONSTRAINT ck_capacity CHECK (park_mount>=0 AND park_spare>=0 AND park_spare<=park_mount)
);
