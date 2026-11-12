-- 文档删除权限点（v1.24：doc 域此前无删除入口，补 doc:delete 与回收站/彻底删除同权）
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('doc:delete', '文档删除');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code IN ('ADMIN', 'ENGINEER') AND p.perm_code = 'doc:delete'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
