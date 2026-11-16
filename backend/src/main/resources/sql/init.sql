-- ============================================
-- 校园摆渡车智能动态调度系统 - 数据库初始化
-- MySQL 8.0
-- ============================================
CREATE DATABASE IF NOT EXISTS shuttle_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE shuttle_db;

-- ---------- 线路 ----------
DROP TABLE IF EXISTS `line`;
CREATE TABLE `line` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `line_no` VARCHAR(10) NOT NULL COMMENT '线路号(1/2/3)',
  `name` VARCHAR(50) NOT NULL COMMENT '线路名称',
  `capacity` INT NOT NULL DEFAULT 13 COMMENT '核定载客量(不含司机)',
  `first_time` TIME NOT NULL DEFAULT '07:00:00' COMMENT '首班时间',
  `last_time` TIME NOT NULL DEFAULT '22:00:00' COMMENT '末班时间',
  `peak_interval_min` INT NOT NULL DEFAULT 5 COMMENT '高峰发车间隔(分钟)',
  `off_interval_min` INT NOT NULL DEFAULT 15 COMMENT '平峰发车间隔(分钟)',
  `enabled` TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB COMMENT='线路';

-- ---------- 站点 ----------
DROP TABLE IF EXISTS `station`;
CREATE TABLE `station` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL COMMENT '站点名称',
  `short_name` VARCHAR(30) COMMENT '简称',
  `campus` VARCHAR(10) NOT NULL DEFAULT 'west' COMMENT 'east/west',
  `qr_code` VARCHAR(64) COMMENT '站点二维码标识',
  `enabled` TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB COMMENT='站点';

-- ---------- 线路-站点关系(有序) ----------
DROP TABLE IF EXISTS `line_station`;
CREATE TABLE `line_station` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `line_id` BIGINT NOT NULL,
  `station_id` BIGINT NOT NULL,
  `direction` VARCHAR(4) NOT NULL COMMENT 'go=去向/回向',
  `seq` INT NOT NULL COMMENT '站点顺序(从1开始)',
  `distance_m` INT DEFAULT 500 COMMENT '到下一站距离(米)',
  `travel_sec` INT DEFAULT 180 COMMENT '到下一站时长(秒)',
  UNIQUE KEY `uk_line_dir_seq` (`line_id`,`direction`,`seq`)
) ENGINE=InnoDB COMMENT='线路站点关系';

-- ---------- 车辆 ----------
DROP TABLE IF EXISTS `vehicle`;
CREATE TABLE `vehicle` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `plate_no` VARCHAR(10) NOT NULL UNIQUE COMMENT '车牌号(1~12)',
  `line_id` BIGINT NOT NULL,
  `capacity` INT NOT NULL DEFAULT 13,
  `status` VARCHAR(20) NOT NULL DEFAULT 'offline' COMMENT 'offline/standby/running/task/returning',
  `current_load` INT NOT NULL DEFAULT 0 COMMENT '当前车内人数',
  `current_station_id` BIGINT COMMENT '当前所在站点',
  `direction` VARCHAR(4) COMMENT 'go/back',
  `running_today` TINYINT NOT NULL DEFAULT 0 COMMENT '当日是否运行',
  `lat` DECIMAL(10,7),
  `lng` DECIMAL(10,7),
  `last_report_time` DATETIME
) ENGINE=InnoDB COMMENT='车辆';

-- ---------- 司机 ----------
DROP TABLE IF EXISTS `driver`;
CREATE TABLE `driver` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(20) NOT NULL,
  `phone` VARCHAR(20),
  `password` VARCHAR(64) NOT NULL DEFAULT '123456',
  `default_vehicle_id` BIGINT COMMENT '默认绑定车辆',
  `status` VARCHAR(20) NOT NULL DEFAULT 'off' COMMENT 'off/on_duty/driving',
  `enabled` TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB COMMENT='司机';

-- ---------- 管理员 ----------
DROP TABLE IF EXISTS `admin`;
CREATE TABLE `admin` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `username` VARCHAR(30) NOT NULL UNIQUE,
  `password` VARCHAR(64) NOT NULL DEFAULT 'admin123',
  `name` VARCHAR(20) NOT NULL,
  `role` VARCHAR(20) NOT NULL DEFAULT 'dispatcher' COMMENT 'dispatcher/admin'
) ENGINE=InnoDB COMMENT='管理员';

-- ---------- 乘客 ----------
DROP TABLE IF EXISTS `passenger`;
CREATE TABLE `passenger` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `open_id` VARCHAR(64) COMMENT '微信openid或临时ID',
  `name` VARCHAR(20),
  `phone` VARCHAR(20),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='乘客';

-- ---------- 乘客请求(签到/预约) ----------
DROP TABLE IF EXISTS `request`;
CREATE TABLE `request` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `passenger_id` BIGINT,
  `station_id` BIGINT NOT NULL COMMENT '候车站点',
  `line_id` BIGINT NOT NULL COMMENT '期望线路',
  `direction` VARCHAR(4) NOT NULL COMMENT 'go/back',
  `dest_station_id` BIGINT COMMENT '目的站点(预约时)',
  `type` VARCHAR(10) NOT NULL DEFAULT 'checkin' COMMENT 'checkin/reserve/cancel',
  `status` VARCHAR(20) NOT NULL DEFAULT 'waiting' COMMENT 'waiting/boarded/cancelled/expired',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='乘客请求';

-- ---------- 事件总线(原子事件) ----------
DROP TABLE IF EXISTS `event`;
CREATE TABLE `event` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `event_no` VARCHAR(64) NOT NULL UNIQUE COMMENT '事件唯一号(幂等键)',
  `type` VARCHAR(20) NOT NULL COMMENT 'arrive/alight/board/depart/checkin/reserve/cancel/calibrate',
  `source` VARCHAR(20) NOT NULL COMMENT 'passenger/driver/camera/admin',
  `vehicle_id` BIGINT,
  `station_id` BIGINT,
  `line_id` BIGINT,
  `direction` VARCHAR(4),
  `count` INT DEFAULT 0 COMMENT '人数变化(正=上车/负=下车)',
  `after_load` INT COMMENT '事件后车内人数',
  `confidence` DECIMAL(3,2) DEFAULT 1.00 COMMENT '置信度',
  `event_time` DATETIME NOT NULL COMMENT '事件发生时间',
  `report_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上报时间',
  `processed` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB COMMENT='事件总线';

-- ---------- 站点队列快照 ----------
DROP TABLE IF EXISTS `station_queue`;
CREATE TABLE `station_queue` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `station_id` BIGINT NOT NULL,
  `line_id` BIGINT NOT NULL,
  `direction` VARCHAR(4) NOT NULL,
  `waiting_count` INT NOT NULL DEFAULT 0 COMMENT '候车人数(融合估计)',
  `confidence` DECIMAL(3,2) DEFAULT 0.50,
  `last_update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_station_line_dir` (`station_id`,`line_id`,`direction`)
) ENGINE=InnoDB COMMENT='站点队列快照';

-- ---------- 调度任务 ----------
DROP TABLE IF EXISTS `task`;
CREATE TABLE `task` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `task_no` VARCHAR(32) NOT NULL UNIQUE,
  `type` VARCHAR(20) NOT NULL DEFAULT 'dispatch' COMMENT 'dispatch/preposition/return',
  `line_id` BIGINT NOT NULL,
  `station_id` BIGINT COMMENT '目标站点',
  `vehicle_id` BIGINT COMMENT '指派车辆',
  `priority` INT NOT NULL DEFAULT 5 COMMENT '1-10',
  `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/assigned/accepted/executing/completed/rejected/timeout/escalated',
  `reason_code` VARCHAR(50) COMMENT 'AI理由码',
  `confidence` DECIMAL(3,2) DEFAULT 0.80,
  `suggestion` TEXT COMMENT 'AI建议描述',
  `assigned_at` DATETIME,
  `completed_at` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='调度任务';

-- ---------- 预警 ----------
DROP TABLE IF EXISTS `alert`;
CREATE TABLE `alert` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `level` VARCHAR(10) NOT NULL COMMENT 'yellow/orange/red',
  `type` VARCHAR(30) NOT NULL COMMENT 'surge/offline/delay/timeout/overload',
  `station_id` BIGINT,
  `vehicle_id` BIGINT,
  `line_id` BIGINT,
  `message` VARCHAR(200),
  `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/confirmed/suppressed/resolved',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `resolved_at` DATETIME
) ENGINE=InnoDB COMMENT='预警';

-- ---------- 课表(潜在需求先验) ----------
DROP TABLE IF EXISTS `course_schedule`;
CREATE TABLE `course_schedule` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `building` VARCHAR(50) NOT NULL COMMENT '教学楼',
  `end_time` TIME NOT NULL COMMENT '下课时间',
  `student_count` INT NOT NULL COMMENT '下课人数',
  `nearby_station_id` BIGINT COMMENT '就近站点',
  `conversion_rate` DECIMAL(3,2) NOT NULL DEFAULT 0.30 COMMENT '乘车转化率',
  `weekday` TINYINT NOT NULL DEFAULT 0 COMMENT '0=每天 1-7=周一至周日'
) ENGINE=InnoDB COMMENT='课表';

-- ---------- 运营统计 ----------
DROP TABLE IF EXISTS `stats`;
CREATE TABLE `stats` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `stat_date` DATE NOT NULL,
  `line_id` BIGINT NOT NULL,
  `station_id` BIGINT,
  `time_slot` VARCHAR(20) COMMENT '如 08:00-08:15',
  `waiting_avg` INT,
  `leftover_avg` INT,
  `load_rate` DECIMAL(4,2),
  `task_complete_rate` DECIMAL(4,2),
  `data_source` VARCHAR(10) NOT NULL DEFAULT 'measured' COMMENT 'measured/inferred/simulated'
) ENGINE=InnoDB COMMENT='运营统计';

-- ============================================
-- 初始数据
-- ============================================

-- 线路
INSERT INTO `line` (`line_no`,`name`,`capacity`) VALUES
('1','一号线',13),('2','二号线',13),('3','三号线',13);

-- 站点 (id: 1-21)
INSERT INTO `station` (`id`,`name`,`short_name`,`campus`,`qr_code`) VALUES
(1,'双馨园食堂','双馨园','east','ST001'),
(2,'东校区体育馆（美术学院）','东体育馆','east','ST002'),
(3,'博学西路口','博学西口','east','ST003'),
(4,'东（西）楼','东西楼','west','ST004'),
(5,'西一区体育场','西一体育场','west','ST005'),
(6,'材料与冶金学院','材冶学院','west','ST006'),
(7,'西校区二食堂','西二食堂','west','ST007'),
(8,'专家公寓（西校区校医院）','专家公寓','west','ST008'),
(9,'西校区三食堂','西三食堂','west','ST009'),
(10,'精细化工中心','精细化工','east','ST010'),
(11,'博学楼','博学楼','east','ST011'),
(12,'朝阳楼','朝阳楼','east','ST012'),
(13,'东校区大门','东校门','east','ST013'),
(14,'赓正楼','赓正楼','west','ST014'),
(15,'明俊楼2号门路口','明俊楼','west','ST015'),
(16,'西校区体育馆（五食堂）','五食堂','west','ST016'),
(17,'雅文楼','雅文楼','west','ST017'),
(18,'西校区校内交通车站','交通车站','west','ST018'),
(19,'西校区大门','西校门','west','ST019'),
(20,'崇学楼','崇学楼','west','ST020'),
(21,'西校区校内交通车站路口','车站路口','west','ST021');

-- 1号线去向: 1→2→3→4→5→6→7→8→9
INSERT INTO `line_station` (`line_id`,`station_id`,`direction`,`seq`,`distance_m`,`travel_sec`) VALUES
(1,1,'go',1,600,240),(1,2,'go',2,500,200),(1,3,'go',3,800,300),(1,4,'go',4,700,260),
(1,5,'go',5,600,220),(1,6,'go',6,500,200),(1,7,'go',7,400,160),(1,8,'go',8,500,200),(1,9,'go',9,0,0);
-- 1号线回向: 9→8→7→6→5→4→3→2→10→11→12→13→1
INSERT INTO `line_station` (`line_id`,`station_id`,`direction`,`seq`,`distance_m`,`travel_sec`) VALUES
(1,9,'back',1,500,200),(1,8,'back',2,400,160),(1,7,'back',3,500,200),(1,6,'back',4,600,220),
(1,5,'back',5,700,260),(1,4,'back',6,800,300),(1,3,'back',7,500,200),(1,2,'back',8,600,240),
(1,10,'back',9,400,160),(1,11,'back',10,300,120),(1,12,'back',11,400,160),(1,13,'back',12,500,200),(1,1,'back',13,0,0);

-- 2号线去向: 1→2→3→4→14→15→16→17→18
INSERT INTO `line_station` (`line_id`,`station_id`,`direction`,`seq`,`distance_m`,`travel_sec`) VALUES
(2,1,'go',1,600,240),(2,2,'go',2,500,200),(2,3,'go',3,800,300),(2,4,'go',4,600,220),
(2,14,'go',5,500,200),(2,15,'go',6,400,160),(2,16,'go',7,600,240),(2,17,'go',8,500,200),(2,18,'go',9,0,0);
-- 2号线回向: 18→17→16→15→14→4→3→2→10→11→12→13→1
INSERT INTO `line_station` (`line_id`,`station_id`,`direction`,`seq`,`distance_m`,`travel_sec`) VALUES
(2,18,'back',1,500,200),(2,17,'back',2,600,240),(2,16,'back',3,400,160),(2,15,'back',4,500,200),
(2,14,'back',5,600,220),(2,4,'back',6,800,300),(2,3,'back',7,500,200),(2,2,'back',8,600,240),
(2,10,'back',9,400,160),(2,11,'back',10,300,120),(2,12,'back',11,400,160),(2,13,'back',12,500,200),(2,1,'back',13,0,0);

-- 3号线去向: 1→2→3→4→19→20→16→17→21→9
INSERT INTO `line_station` (`line_id`,`station_id`,`direction`,`seq`,`distance_m`,`travel_sec`) VALUES
(3,1,'go',1,600,240),(3,2,'go',2,500,200),(3,3,'go',3,800,300),(3,4,'go',4,700,260),
(3,19,'go',5,500,200),(3,20,'go',6,400,160),(3,16,'go',7,600,240),(3,17,'go',8,500,200),
(3,21,'go',9,400,160),(3,9,'go',10,0,0);
-- 3号线回向: 9→21→17→16→20→19→4→3→2→10→11→12→13→1
INSERT INTO `line_station` (`line_id`,`station_id`,`direction`,`seq`,`distance_m`,`travel_sec`) VALUES
(3,9,'back',1,400,160),(3,21,'back',2,500,200),(3,17,'back',3,500,200),(3,16,'back',4,600,240),
(3,20,'back',5,400,160),(3,19,'back',6,700,260),(3,4,'back',7,800,300),(3,3,'back',8,500,200),
(3,2,'back',9,600,240),(3,10,'back',10,400,160),(3,11,'back',11,300,120),(3,12,'back',12,400,160),(3,13,'back',13,500,200),(3,1,'back',14,0,0);

-- 车辆: 1-4→1号线, 5-8→2号线, 9-12→3号线
INSERT INTO `vehicle` (`plate_no`,`line_id`,`capacity`,`status`) VALUES
('1',1,13,'offline'),('2',1,13,'offline'),('3',1,13,'offline'),('4',1,13,'offline'),
('5',2,13,'offline'),('6',2,13,'offline'),('7',2,13,'offline'),('8',2,13,'offline'),
('9',3,13,'offline'),('10',3,13,'offline'),('11',3,13,'offline'),('12',3,13,'offline');

-- 司机(仿真)
INSERT INTO `driver` (`name`,`phone`,`default_vehicle_id`,`status`) VALUES
('司机01','13800000001',1,'off'),('司机02','13800000002',2,'off'),('司机03','13800000003',3,'off'),('司机04','13800000004',4,'off'),
('司机05','13800000005',5,'off'),('司机06','13800000006',6,'off'),('司机07','13800000007',7,'off'),('司机08','13800000008',8,'off'),
('司机09','13800000009',9,'off'),('司机10','13800000010',10,'off'),('司机11','13800000011',11,'off'),('司机12','13800000012',12,'off');

-- 管理员
INSERT INTO `admin` (`username`,`password`,`name`,`role`) VALUES
('admin','admin123','系统管理员','admin'),
('dispatcher','123456','调度员01','dispatcher');

-- 课表(假定): 下课时间/楼栋/人数/就近站点/转化率
INSERT INTO `course_schedule` (`building`,`end_time`,`student_count`,`nearby_station_id`,`conversion_rate`,`weekday`) VALUES
('东校区教学楼','08:00:00',120,2,0.25,0),
('博学楼','10:00:00',80,11,0.30,0),
('东校区教学楼','12:00:00',150,2,0.35,0),
('赓正楼','12:00:00',100,14,0.30,0),
('崇学楼','14:00:00',90,20,0.25,0),
('明俊楼','16:00:00',110,15,0.28,0),
('东校区教学楼','18:00:00',130,2,0.40,0),
('雅文楼','18:00:00',70,17,0.30,0);

-- 站点队列初始化(全0)
INSERT INTO `station_queue` (`station_id`,`line_id`,`direction`,`waiting_count`)
SELECT ls.station_id, ls.line_id, ls.direction, 0 FROM line_station ls;