-- F2-3 纠偏：V17 的 meta:manage 绑定写的是旧角色名 ADMIN，而 V14 已将 ADMIN 更名为 ADMINS，
-- 导致 0 行命中（SUPER 免检掩盖了该问题）。此处按新角色名幂等补绑。
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS' AND p.perm_code = 'meta:manage'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
