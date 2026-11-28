-- 2026-11-28 审查修复（#5 权限点漂移）：V32/V34 在 V14 改名之后仍按旧角色名 'ADMIN' 绑定，0 行命中——
-- ADMINS 缺 menu:notify / menu:delegate / menu:recycle / doc:delete（V18 同类问题同款修法：按新名幂等补绑）；
-- V15 曾把 menu:system 授予 ENGINEER/VIEWER（全部菜单基线），只读角色点开系统页必然 403——收回。
-- 说明：V32/V34 的旧绑定行（若存在）保留不动，仅做增量补绑与定向收回。

-- 1) ADMINS 补绑四个遗漏权限点（幂等）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS'
  AND p.perm_code IN ('menu:notify', 'menu:delegate', 'menu:recycle', 'doc:delete')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 2) 收回 ENGINEER/VIEWER 的 menu:system（系统页 7 个管理页面对只读角色全部不可用，菜单不应展示）
DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT id FROM sys_permission WHERE perm_code = 'menu:system')
  AND role_id IN (SELECT id FROM sys_role WHERE role_code IN ('ENGINEER', 'VIEWER'));
