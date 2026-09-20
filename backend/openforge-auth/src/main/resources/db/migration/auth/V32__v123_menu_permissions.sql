-- v1.23 三模块菜单权限（设计 §4；幂等，对齐 V25/V28 模式）
-- menu:notify      通知中心（铃铛+收件箱页）——全角色
-- menu:delegate    审批委托页 —— 管理角色
-- menu:recycle     回收站页   —— 管理角色（数据恢复属管理操作）

INSERT INTO sys_permission (perm_code, perm_name, perm_type, sort_order)
SELECT 'menu:notify', '通知中心', 'MENU', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE perm_code = 'menu:notify');

INSERT INTO sys_permission (perm_code, perm_name, perm_type, sort_order)
SELECT 'menu:delegate', '审批委托', 'MENU', 21
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE perm_code = 'menu:delegate');

INSERT INTO sys_permission (perm_code, perm_name, perm_type, sort_order)
SELECT 'menu:recycle', '回收站', 'MENU', 22
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE perm_code = 'menu:recycle');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code IN ('ADMIN', 'ENGINEER', 'VIEWER') AND p.perm_code = 'menu:notify'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code IN ('ADMIN', 'ENGINEER') AND p.perm_code IN ('menu:delegate', 'menu:recycle')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id);
