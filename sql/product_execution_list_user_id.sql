-- 执行清单数据权限：部署新版后端前执行，可重复运行。
-- 新记录由服务端写入 user_id；create_by 仅保留为审计字段。
SET @has_user_id = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'product_execution_list' AND column_name = 'user_id');
SET @ddl = IF(@has_user_id = 0,
    'ALTER TABLE product_execution_list ADD COLUMN user_id BIGINT NULL COMMENT ''创建用户ID'' AFTER id',
    'SELECT 1');
PREPARE execution_list_migration FROM @ddl;
EXECUTE execution_list_migration;
DEALLOCATE PREPARE execution_list_migration;

-- 仅迁移尚未关联的旧记录；找不到原用户的记录保持 NULL，不猜测归属。
-- create_by 只在本次历史数据迁移中使用，运行时权限查询使用 user_id。
UPDATE product_execution_list e
INNER JOIN sys_user u ON u.user_name = e.create_by
SET e.user_id = u.user_id
WHERE e.user_id IS NULL;

SET @has_user_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'product_execution_list' AND index_name = 'idx_execution_list_user_id');
SET @ddl = IF(@has_user_index = 0,
    'ALTER TABLE product_execution_list ADD INDEX idx_execution_list_user_id (user_id)',
    'SELECT 1');
PREPARE execution_list_migration FROM @ddl;
EXECUTE execution_list_migration;
DEALLOCATE PREPARE execution_list_migration;

-- 检查无法自动匹配的历史清单，需人工确认归属后补齐 user_id。
SELECT id, file_name, create_by FROM product_execution_list WHERE user_id IS NULL;
