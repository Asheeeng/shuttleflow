-- 为已经安装 V1.3 调度方向迁移的本机仿真库添加行程关联。新建库使用 init.sql。
ALTER TABLE `request`
  ADD COLUMN boarded_vehicle_id BIGINT NULL,
  ADD COLUMN trip_id VARCHAR(64) NULL,
  ADD COLUMN boarded_at DATETIME NULL,
  ADD COLUMN alighted_at DATETIME NULL;
ALTER TABLE `event`
  ADD COLUMN trip_id VARCHAR(64) NULL,
  ADD COLUMN waiting_at_departure INT NULL;
