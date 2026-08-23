-- 初始权限点与 ADMIN 角色绑定（兼容 PostgreSQL 16 与 H2 PostgreSQL 模式）

INSERT INTO sys_permission (perm_code, perm_name) VALUES ('role:create', '创建角色');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('role:assign', '分配用户角色');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('perm:manage', '管理权限点与绑定');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('user:manage', '用户管理');

-- ADMIN 绑定当前全部权限点（拦截器同时内置 ADMIN 免检兜底，双保险）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMIN';
