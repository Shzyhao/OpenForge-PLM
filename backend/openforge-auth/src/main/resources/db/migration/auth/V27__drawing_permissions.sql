-- 图纸管理权限点（v1.20.0）+ ADMINS 绑定（对齐 V24 模式：幂等）
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('drawing:manage', '图纸管理');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS' AND p.perm_code = 'drawing:manage'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
