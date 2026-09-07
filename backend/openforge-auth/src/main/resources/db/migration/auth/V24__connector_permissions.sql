-- 集成编排器固定权限点（集成编排器 MVP 设计 §6）+ ADMINS 绑定
-- 角色名沿用 V14 起的 ADMINS（V18 纠偏先例：旧 ADMIN 名 0 行命中）
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('conn:manage', '集成编排器管理');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('conn:invoke', '连接器调用');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS' AND p.perm_code IN ('conn:manage', 'conn:invoke')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
