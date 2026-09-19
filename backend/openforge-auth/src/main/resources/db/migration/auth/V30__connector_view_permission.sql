-- 连接器读权限点（十轮：连接器列表/详情此前无注解，与 DLQ 的 conn:manage 不一致）。
-- 查看面向更宽角色开放（管理员/工程师/查看者），执行仍由 conn:invoke + 连接器级 ACL 白名单约束。
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('conn:view', '连接器查看');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code IN ('ADMINS', 'ENGINEER', 'VIEWER') AND p.perm_code = 'conn:view'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
