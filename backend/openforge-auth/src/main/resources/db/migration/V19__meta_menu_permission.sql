-- F2-4：低代码菜单权限（对象建模/动态数据入口）+ ADMINS 绑定（幂等）
INSERT INTO sys_permission (perm_code, perm_name, perm_type, sort_order)
SELECT 'menu:meta', '低代码', 'MENU', 11
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE perm_code = 'menu:meta');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS' AND p.perm_code = 'menu:meta'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
