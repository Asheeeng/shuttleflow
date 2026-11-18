-- ShuttleFlow V1.3 调度方向迁移。
-- 本文件只扩展任务表；不会删除或重置任何数据。执行前必须备份并确认当前库名。
-- 历史任务的方向不可从任务表可靠还原，保持 NULL 并由管理员人工核对；
-- TaskService 会拒绝自动派发方向缺失的历史任务。
SET @v13_direction_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'task' AND column_name = 'direction'
);
SET @v13_direction_sql = IF(
  @v13_direction_exists = 0,
  'ALTER TABLE task ADD COLUMN direction VARCHAR(4) NULL COMMENT ''任务目标方向(go/back)'' AFTER station_id',
  'SELECT ''task.direction 已存在，跳过迁移'''
);
PREPARE v13_direction_stmt FROM @v13_direction_sql;
EXECUTE v13_direction_stmt;
DEALLOCATE PREPARE v13_direction_stmt;

SET @v13_idx_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'task' AND index_name = 'idx_task_line_direction_status'
);
SET @v13_idx_sql = IF(
  @v13_idx_exists = 0,
  'CREATE INDEX idx_task_line_direction_status ON task(line_id,direction,status)',
  'SELECT ''调度方向索引已存在，跳过迁移'''
);
PREPARE v13_idx_stmt FROM @v13_idx_sql;
EXECUTE v13_idx_stmt;
DEALLOCATE PREPARE v13_idx_stmt;
