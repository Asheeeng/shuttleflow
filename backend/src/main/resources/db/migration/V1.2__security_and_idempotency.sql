-- V1.2 生产升级脚本：只扩展列与约束，不删除表或业务数据。
-- 执行前先按 docs/deploy/debian12.md 检查重复手机号、车辆绑定及超容量数据。
ALTER TABLE passenger ADD COLUMN password_hash VARCHAR(100) NULL;
ALTER TABLE request ADD COLUMN active_key VARCHAR(64) NULL COMMENT '活动请求幂等键';
ALTER TABLE task ADD COLUMN dispatch_key VARCHAR(64) NULL COMMENT '自动任务幂等键';
ALTER TABLE passenger ADD UNIQUE KEY uk_passenger_phone (phone);
ALTER TABLE driver ADD UNIQUE KEY uk_driver_phone (phone), ADD UNIQUE KEY uk_driver_vehicle (default_vehicle_id);
ALTER TABLE request ADD UNIQUE KEY uk_request_active_key (active_key);
ALTER TABLE task ADD UNIQUE KEY uk_task_dispatch_key (dispatch_key);
ALTER TABLE vehicle
  ADD CONSTRAINT chk_vehicle_capacity_positive CHECK (capacity > 0),
  ADD CONSTRAINT chk_vehicle_load_in_capacity CHECK (current_load >= 0 AND current_load <= capacity);
ALTER TABLE request ADD CONSTRAINT chk_request_status CHECK (status IN ('waiting','boarded','cancelled','expired'));
ALTER TABLE task ADD CONSTRAINT chk_task_status CHECK (status IN ('pending','assigned','accepted','executing','completed','rejected','timeout','escalated'));
