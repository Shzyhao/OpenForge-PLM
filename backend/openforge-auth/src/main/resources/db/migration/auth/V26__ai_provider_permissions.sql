-- AI 供应商管理权限点（集成编排器 MVP 设计 §12.1）+ ADMINS 绑定（幂等）
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('ai:manage', 'AI 模型配置管理');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS' AND p.perm_code = 'ai:manage'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
