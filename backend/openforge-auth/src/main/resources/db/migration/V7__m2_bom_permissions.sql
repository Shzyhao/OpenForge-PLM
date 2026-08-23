-- M2 BOM 权限点 + ADMIN 绑定
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('bom:manage', 'BOM管理');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('doc:create', '创建文档');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('doc:write', '文档编辑/检入检出');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMIN'
  AND p.perm_code IN ('bom:manage', 'doc:create', 'doc:write');
