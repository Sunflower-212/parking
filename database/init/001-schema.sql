-- 创建数据库
CREATE DATABASE IF NOT EXISTS parking_springboot CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 使用数据库
use parking_springboot;

-- 用户表
create table user (
                      id int unsigned primary key auto_increment comment '用户ID',
                      user_name varchar(20) not null unique comment '用户名',
                      password varchar(100) not null comment '密码',
                      name varchar(20)  default '' comment '姓名',
                      plate_num varchar(10) default null unique comment '车牌号',
                      car_color varchar(20) default '' comment '车辆颜色',
                      tel_num varchar(11) default null unique comment '电话号码',
                      balance decimal(14,2) not null default 0.0 comment '账户余额',
                      status int not null default 0 comment '用户身份：0-普通，1-vip',
                      user_pic varchar(512) default '' comment '头像',
                      vip_start_time datetime default null comment '会员开始时间',
                      vip_end_time datetime default null comment '会员结束时间',
                      create_time datetime not null comment '创建时间',
                      update_time datetime not null comment '修改时间',
                      check(balance>=0) ,
                      check(status=0 or status=1)
) comment '用户表';


-- 管理员表
create table admin (
                      id int unsigned primary key auto_increment comment '管理员ID',
                      admin_name varchar(20) not null unique comment '管理员账号',
                      password varchar(100) not null comment '密码',
                      permissions int not null default 0 comment '管理员权限',
                      create_time datetime not null comment '创建时间',
                      update_time datetime not null comment '修改时间',
                      check(permissions=0 or permissions=1)
) comment '管理员表';


-- 计费规则表
create table rule (
                      id int unsigned primary key auto_increment comment '计费规则ID',
                      rule_name varchar(20) not null unique comment '规则名称',
                      free_time int unsigned not null comment '免费时长(min)',
                      charging_unit int unsigned not null comment '计费单位(min)',
                      unit_cost decimal(12,2) not null comment '单位时长费用',
                      create_time datetime not null comment '创建时间',
                      update_time datetime not null comment '修改时间',
                      check(unit_cost>=0)
) comment '计费规则表';


-- 停车场表
create table parking (
                      id int unsigned primary key auto_increment comment '停车场ID',
                      admin_id int unsigned not null comment '所属管理员ID',
                      park_name varchar(20)  not null unique comment '停车场名称',
                      park_address varchar(40)  not null comment '停车场地址',
                      park_mount int not null comment '停车场车位数',
                      park_spare int not null comment '停车场剩余车位数',
                      park_storey int not null comment '停车场层数',
                      rule_id int unsigned not null comment '计费规则ID',
                      create_time datetime not null comment '创建时间',
                      update_time datetime not null comment '修改时间',
	                  constraint fk_parking_admin foreign key(admin_id) references admin(id),-- 外键约束
                      constraint fk_parking_rule foreign key(rule_id) references rule(id),-- 外键约束
                      check(park_spare>=0 and park_spare<=park_mount)
) comment '停车场表';



-- 入场表
create table enter (
                      id int unsigned primary key auto_increment comment '入场ID',
                      plate_num varchar(10) not null comment '车牌号',
                      user_id int unsigned not null comment '用户ID',
                      park_id int unsigned not null comment '停车场ID',
                      enter_time datetime not null comment '入场时间',
                      flag int not null default 0 comment '0-未入场,1-在场,2-离场',
                      reservation int not null default 0 comment '0-未预约,1-预约,2-取消或过期',
                      space_id int unsigned null,
                      reserved_at datetime(3) null,
                      expires_at datetime(3) null,
                      update_time datetime(3) not null default current_timestamp(3),
                      active_user_id int unsigned generated always as (case when flag=1 or (flag=0 and reservation=1) then user_id else null end) stored,
                      active_space_id int unsigned generated always as (case when flag=1 or (flag=0 and reservation=1) then space_id else null end) stored,
                      unique key uk_enter_active_user(active_user_id),
                      unique key uk_enter_active_space(active_space_id),
                      key idx_enter_pending(flag,reservation,expires_at),
                      constraint fk_enter_user foreign key(user_id) references user(id),-- 外键约束
                      constraint fk_enter_park foreign key(park_id) references parking(id),-- 外键约束
                      check(flag in (0,1,2)),
                      check(reservation in (0,1,2))
) comment '入场表';


-- 订单信息表
create table orders (
                      id int unsigned primary key auto_increment comment '订单ID',
                      plate_num varchar(10) not null comment '车牌号',
                      user_id int not null comment '用户ID',
                      park_id int not null comment '停车场ID',
                      enter_time datetime not null comment '入场时间',
                      exit_time datetime not null comment '出场时间',
                      parking_duration int not null comment '停车时长',
                      moneys decimal(14,2) not null comment '收费金额',
                      create_time datetime not null comment '创建时间',
                      check(moneys>=0 )
) comment '订单信息表';


-- 车位信息表
create table spaces(
                      id int unsigned primary key auto_increment comment '车位ID',
                      park_id int unsigned not null comment '所属停车场',
                      spaces_name varchar(10) not null comment '车位名称',
                      spaces_col int unsigned not null comment '车位所在列',
                      spaces_row int unsigned not null comment '车位所在行',
                      spaces_storey int unsigned not null comment '车位所在层数',
                      spaces_flag int unsigned not null comment '车位标记(0-空闲车位，1-被占用车位，2-道路，3-墙壁，4-车辆出入口)',
                      user_name varchar(20) default null comment '使用者姓名',
                      active_enter_id int unsigned null,
                      unique key uk_space_coordinates(park_id,spaces_storey,spaces_row,spaces_col),
                      unique key uk_space_entry(active_enter_id),
                      create_time datetime not null comment '创建时间',
                      update_time datetime not null comment '修改时间',
                      constraint fk_spaces_parking foreign key(park_id) references parking(id), -- 外键约束
                      check(spaces_flag in(0,1,2,3,4))

)comment '车位信息表';


-- 充值信息表
create table recharges(
                      id int unsigned primary key auto_increment comment '充值信息ID',
                      user_id int unsigned not null comment '充值人ID',
                      amounts decimal(14,2) not null comment '充值金额',
                      flag int not null default 0 comment '支付状态(0-未支付,1-已支付)',
                      pay_method int null comment '支付方式(0-支付宝,1-微信)',
                      pay_no varchar(64) default '' comment '付款订单编号',
                      create_time datetime not null comment '充值时间',
                      merchant_order_no varchar(64) NULL,
                      expires_at datetime(3) NULL,
                      payment_state varchar(16) NOT NULL DEFAULT 'VERIFYING',
                      cancel_requested boolean NOT NULL DEFAULT false,
                      UNIQUE KEY uk_recharge_merchant_order(merchant_order_no),
                      pay_time datetime null comment '支付时间',
                      constraint fk_recharges_user foreign key(user_id) references user(id),-- 外键约束
                      check(pay_method in (0,1)),
                      check(flag in(0,1,2)),
                      check(amounts>=0)
);

-- 历史会话表
create table historicalSession(
    id bigint unsigned primary key auto_increment comment '主键ID',
    chatId varchar(255) not null unique comment '聊天ID',
    type varchar(255) not null comment '类型',
    userId varchar(255) not null comment '用户ID',
    create_time datetime not null comment '创建时间',
    update_time datetime not null default current_timestamp
) comment '历史会话表';


CREATE TABLE reservation_event (
  reservation_id int unsigned PRIMARY KEY,
  expires_at datetime(3) NOT NULL,
  delivered_at datetime(3) NULL,
  completed_at datetime(3) NULL,
  attempts int NOT NULL DEFAULT 0,
  last_error varchar(255) NULL,
  KEY idx_reservation_event_pending(completed_at),
  FOREIGN KEY(reservation_id) REFERENCES enter(id)
) ENGINE=InnoDB;
CREATE UNIQUE INDEX uk_recharges_provider_trade ON recharges ((nullif(pay_no,'')));


-- Query indexes (migration 003).
CREATE INDEX idx_orders_user_time ON `orders` (user_id,create_time DESC,id DESC);
CREATE INDEX idx_orders_park_time ON `orders` (park_id,create_time DESC,id DESC);
CREATE INDEX idx_orders_time ON `orders` (create_time DESC,id DESC);
CREATE INDEX idx_enter_user_time ON `enter` (user_id,enter_time DESC,id DESC);
CREATE INDEX idx_enter_park_time ON `enter` (park_id,enter_time DESC,id DESC);
CREATE INDEX idx_enter_time_flag ON `enter` (enter_time,flag);
CREATE INDEX idx_spaces_available ON `spaces` (park_id,spaces_flag,active_enter_id,id);
CREATE INDEX idx_recharges_user_time ON `recharges` (user_id,create_time DESC,id DESC);
CREATE INDEX idx_recharges_flag_time ON `recharges` (flag,create_time,id);
CREATE INDEX idx_recharges_time ON `recharges` (create_time DESC,id DESC);
CREATE INDEX idx_user_created ON `user` (create_time,id);
CREATE INDEX idx_user_vip_expiry ON `user` (status,vip_end_time,id);
CREATE INDEX idx_session_user_type_time ON `historicalSession` (userId,type,update_time DESC,id DESC);

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
