-- M5 知识库权限点 + ADMIN 绑定
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('knowledge:manage', '知识库管理');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('knowledge:read', '知识库阅读');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMIN' AND p.perm_code IN ('knowledge:manage', 'knowledge:read');
