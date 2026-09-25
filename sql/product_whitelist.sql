-- 白名单库及独立菜单；可重复执行，不删除已有数据。
CREATE TABLE IF NOT EXISTS product_whitelist (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '白名单主键',
    filter_word varchar(100) NOT NULL COMMENT '过滤词',
    word_key varchar(200) COLLATE utf8mb4_bin NOT NULL COMMENT '标准化过滤词查重键',
    match_type varchar(16) NOT NULL COMMENT 'UNIT计量单位，WORD词汇',
    match_content mediumtext NOT NULL COMMENT '匹配内容，每行一项',
    match_count int NOT NULL DEFAULT 0 COMMENT '匹配内容数量',
    status char(1) NOT NULL DEFAULT '0' COMMENT '0启用，1禁用',
    create_by varchar(64) NOT NULL DEFAULT '',
    create_time datetime NOT NULL,
    update_by varchar(64) NOT NULL DEFAULT '',
    update_time datetime DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_word_type (word_key, match_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='白名单库';

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '白名单库', 0, 8, 'whitelist', 'product/whitelist/index', 1, 1, 'C', '0', '0', 'product:whitelist:list', 'list', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'product/whitelist/index');
SET @whitelist_menu = (SELECT menu_id FROM sys_menu WHERE component = 'product/whitelist/index' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '白名单新增及追加', @whitelist_menu, 1, '', 'F', '0', '0', 'product:whitelist:add', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:whitelist:add');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '白名单修改及状态', @whitelist_menu, 2, '', 'F', '0', '0', 'product:whitelist:edit', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:whitelist:edit');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, create_by, create_time)
SELECT '白名单删除', @whitelist_menu, 3, '', 'F', '0', '0', 'product:whitelist:remove', 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'product:whitelist:remove');
