-- 集成编排器菜单权限（对齐 V19 模式：幂等）+ ADMINS 绑定
INSERT INTO sys_permission (perm_code, perm_name, perm_type, sort_order)
SELECT 'menu:connector', '集成编排器', 'MENU', 12
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE perm_code = 'menu:connector');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS' AND p.perm_code = 'menu:connector'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
