-- 部署新版前，在项目现有数据库中执行本文件；可重复执行，不清空已有词库。
CREATE TABLE IF NOT EXISTS product_word_library (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '词库ID',
    owner_id bigint NOT NULL COMMENT '所属用户ID',
    name varchar(50) NOT NULL COMMENT '词库名称，同一用户内唯一',
    title_words mediumtext NOT NULL COMMENT '标题过滤词，按换行分隔',
    image_words mediumtext NOT NULL COMMENT '图片文字过滤词，按换行分隔',
    detect_phones tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否检测图片中的手机号',
    create_by varchar(64) NOT NULL DEFAULT '',
    create_time datetime NOT NULL,
    update_by varchar(64) NOT NULL DEFAULT '',
    update_time datetime DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_name (owner_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品过滤词库';

-- 商品功能统一使用数据库菜单，之后可以在菜单管理中调整显示、权限和排序。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '商品检测', 0, 4, 'product', 'product/scan/index', 1, 1, 'C', '0', '0', 'product:scan:use', 'shopping', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'product/scan/index');
SET @product_menu = (SELECT menu_id FROM sys_menu WHERE perms = 'product:scan:use' AND menu_type = 'M' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '商品检测', @product_menu, 1, 'scan', 'product/scan/index', 1, 1, 'C', '0', '0', 'product:scan:use', 'shopping', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = 'scan' AND component = 'product/scan/index');
-- 兼容已执行旧版脚本：将父目录隐藏，并把商品检测子菜单提升为唯一顶层菜单。
UPDATE sys_menu parent JOIN sys_menu child ON child.component = 'product/scan/index'
SET parent.visible = '1', child.parent_id = 0, child.path = 'product', child.order_num = 4,
    child.menu_name = '商品检测', child.icon = 'shopping'
WHERE parent.menu_type = 'M' AND parent.perms = 'product:scan:use';
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '已检测过滤', 0, 6, 'checked-filter', 'product/checked-filter/index', 1, 1, 'C', '0', '0', 'product:scan:use', 'checkbox', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'product/checked-filter/index');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '过滤词库', 0, 5, 'word-library', 'product/word-library/index', 1, 1, 'C', '0', '0', 'product:wordLibrary:list', 'list', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:wordLibrary:list');
SET @library_menu = (SELECT menu_id FROM sys_menu WHERE perms = 'product:wordLibrary:list' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '词库新增', @library_menu, 1, '', 'F', '0', '0', 'product:wordLibrary:add', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:wordLibrary:add');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '词库修改', @library_menu, 2, '', 'F', '0', '0', 'product:wordLibrary:edit', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:wordLibrary:edit');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '词库删除', @library_menu, 3, '', 'F', '0', '0', 'product:wordLibrary:remove', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:wordLibrary:remove');

-- 管理员默认可用；其他角色请在系统管理 → 角色管理中勾选过滤词库及所需按钮权限。
