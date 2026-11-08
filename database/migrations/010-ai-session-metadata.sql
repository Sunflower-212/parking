USE parking_springboot;
DROP PROCEDURE IF EXISTS parking_ai_session_upgrade;
DELIMITER $$
CREATE PROCEDURE parking_ai_session_upgrade()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='historicalSession' AND column_name='title') THEN
        ALTER TABLE historicalSession ADD COLUMN title varchar(128) NULL;
    END IF;
END$$
DELIMITER ;
CALL parking_ai_session_upgrade();
DROP PROCEDURE parking_ai_session_upgrade;
