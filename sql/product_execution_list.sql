-- 部署前在现有业务数据库执行；可重复执行，不清空数据。
CREATE TABLE IF NOT EXISTS product_execution_list (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '执行清单主键',
    platform varchar(10) NOT NULL COMMENT '平台 taobao/1688',
    file_name varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '清单名称，完整文件名，全局唯一，区分大小写',
    product_count int NOT NULL COMMENT '去重商品数量',
    record_count int NOT NULL COMMENT '原始商品记录数',
    file_path varchar(255) NOT NULL COMMENT '原始文件相对路径，文件保存在服务器私有目录',
    remark varchar(500) DEFAULT '' COMMENT '备注',
    create_by varchar(64) DEFAULT '' COMMENT '创建者',
    create_time datetime NOT NULL COMMENT '创建时间',
    update_by varchar(64) DEFAULT '' COMMENT '更新者',
    update_time datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_file_name (file_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品执行清单';

-- 旧表升级：先增加路径列；启动新版后自动将 source_csv 安全转存到文件，再删除正文列。
SET @execution_schema_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_execution_list' AND column_name = 'file_path') = 0,
    'ALTER TABLE product_execution_list ADD COLUMN file_path varchar(255) NULL COMMENT ''原始文件相对路径''', 'SELECT 1');
PREPARE execution_schema_stmt FROM @execution_schema_sql;
EXECUTE execution_schema_stmt;
DEALLOCATE PREPARE execution_schema_stmt;

-- name 是旧版冗余字段，真实文件名始终保存在 file_name。
SET @execution_schema_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_execution_list' AND column_name = 'name') > 0,
    'ALTER TABLE product_execution_list DROP COLUMN name', 'SELECT 1');
PREPARE execution_schema_stmt FROM @execution_schema_sql;
EXECUTE execution_schema_stmt;
DEALLOCATE PREPARE execution_schema_stmt;

-- 每个账号保留自己的过滤快照，不互相覆盖；空结果以0计数，未过滤则没有记录。
CREATE TABLE IF NOT EXISTS product_execution_list_filter (
    list_id bigint NOT NULL COMMENT '执行清单主键',
    owner_id bigint NOT NULL COMMENT '过滤用户ID',
    filtered_file_path varchar(255) NOT NULL COMMENT '过滤CSV文件相对路径',
    filtered_record_count int NOT NULL COMMENT '过滤后记录数',
    filtered_product_count int NOT NULL COMMENT '过滤后去重商品数',
    filtered_time datetime NOT NULL COMMENT '过滤时间',
    filter_version varchar(36) NOT NULL COMMENT '过滤快照版本',
    PRIMARY KEY (list_id, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行清单按账号保存的过滤结果';

-- 独立一级菜单，使用若依动态菜单加载，无需增加静态路由。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '执行清单', 0, 7, 'execution-list', 'product/execution-list/index', 1, 1, 'C', '0', '0', 'product:executionList:list', 'clipboard', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:executionList:list');
SET @execution_menu = (SELECT menu_id FROM sys_menu WHERE perms = 'product:executionList:list' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '执行清单查询', @execution_menu, 1, '#', 'F', '0', '0', 'product:executionList:query', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:executionList:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '执行清单新增', @execution_menu, 2, '#', 'F', '0', '0', 'product:executionList:add', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:executionList:add');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '执行清单修改', @execution_menu, 3, '#', 'F', '0', '0', 'product:executionList:edit', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:executionList:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '执行清单删除', @execution_menu, 4, '#', 'F', '0', '0', 'product:executionList:remove', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:executionList:remove');

-- 管理员默认可用；其他角色在角色管理中分配执行清单菜单及按钮权限。

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '过滤已检测', @execution_menu, 5, '#', 'F', '0', '0', 'product:executionList:filter', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:executionList:filter');
