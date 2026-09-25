-- 旧版执行清单升级：在业务数据库执行，配合新版前后端一起部署。
-- 仅删除冗余 name 列，保留 file_name、文件内容、数量和其他数据；可重复执行。
SET @execution_drop_name_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE product_execution_list DROP COLUMN name',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'product_execution_list'
      AND column_name = 'name'
);
PREPARE execution_drop_name_stmt FROM @execution_drop_name_sql;
EXECUTE execution_drop_name_stmt;
DEALLOCATE PREPARE execution_drop_name_stmt;
