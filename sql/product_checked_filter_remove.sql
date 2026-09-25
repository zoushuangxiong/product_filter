-- 删除已停用的独立“已检测过滤”菜单及角色关联，可重复执行。
-- 按组件定位，不按共享的 product:scan:use 权限删除，以保留商品检测菜单。
START TRANSACTION;
DELETE role_menu FROM sys_role_menu role_menu
INNER JOIN sys_menu menu ON menu.menu_id = role_menu.menu_id
WHERE menu.component = 'product/checked-filter/index';
DELETE FROM sys_menu WHERE component = 'product/checked-filter/index';
COMMIT;
