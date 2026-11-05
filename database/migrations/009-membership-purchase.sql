-- Stop application writers before upgrading. Repeatable after a partial upgrade.
USE parking_springboot;
CREATE TABLE IF NOT EXISTS membership_order (
    id varchar(36) PRIMARY KEY,
    user_id int unsigned NOT NULL,
    request_key varchar(64) NOT NULL,
    plan_code varchar(16) NOT NULL,
    plan_name varchar(16) NOT NULL,
    months int NOT NULL,
    price decimal(14,2) NOT NULL,
    reserved_amount decimal(14,2) NOT NULL,
    supplement_amount decimal(14,2) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    active_user_id int unsigned GENERATED ALWAYS AS (CASE WHEN state='PENDING' THEN user_id ELSE NULL END) STORED,
    expires_at datetime(3) NULL,
    starts_at datetime NULL,
    ends_at datetime NULL,
    renewed boolean NOT NULL DEFAULT false,
    created_at datetime(3) NOT NULL,
    completed_at datetime(3) NULL,
    UNIQUE KEY uk_membership_request(user_id,request_key),
    UNIQUE KEY uk_membership_active(active_user_id),
    KEY idx_membership_user_time(user_id,created_at),
    CONSTRAINT fk_membership_user FOREIGN KEY(user_id) REFERENCES user(id),
    CHECK(state IN ('PENDING','SUCCEEDED','CLOSED')),
    CHECK(price>0 AND reserved_amount>=0 AND supplement_amount>=0 AND reserved_amount+supplement_amount=price),
    CHECK(months IN (1,3,12))
) ENGINE=InnoDB;
DROP PROCEDURE IF EXISTS parking_membership_upgrade;
DELIMITER $$
CREATE PROCEDURE parking_membership_upgrade()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='reserved_balance') THEN
        ALTER TABLE user ADD reserved_balance decimal(14,2) NOT NULL DEFAULT 0;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='user' AND constraint_name='ck_user_reserved_balance') THEN
        ALTER TABLE user ADD CONSTRAINT ck_user_reserved_balance CHECK(reserved_balance>=0 AND reserved_balance<=balance);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='recharges' AND column_name='business_type') THEN
        ALTER TABLE recharges ADD business_type varchar(24) NOT NULL DEFAULT 'NORMAL_RECHARGE';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='recharges' AND column_name='description') THEN
        ALTER TABLE recharges ADD description varchar(100) NOT NULL DEFAULT '普通充值';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='recharges' AND column_name='membership_order_id') THEN
        ALTER TABLE recharges ADD membership_order_id varchar(36) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='recharges' AND index_name='uk_membership_record') THEN
        ALTER TABLE recharges ADD UNIQUE KEY uk_membership_record(membership_order_id,business_type);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='recharges' AND constraint_name='fk_recharge_membership') THEN
        ALTER TABLE recharges ADD CONSTRAINT fk_recharge_membership FOREIGN KEY(membership_order_id) REFERENCES membership_order(id);
    END IF;
END$$
DELIMITER ;
CALL parking_membership_upgrade();
DROP PROCEDURE parking_membership_upgrade;
