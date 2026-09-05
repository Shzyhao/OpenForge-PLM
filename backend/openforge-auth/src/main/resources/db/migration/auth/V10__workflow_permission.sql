-- M3 流程权限点 + ADMIN 绑定
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('workflow:manage', '流程定义与实例管理');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMIN' AND p.perm_code = 'workflow:manage';
