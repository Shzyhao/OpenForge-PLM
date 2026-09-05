-- M2 物料域权限点 + ADMIN 绑定
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('part:create', '创建物料');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('part:update', '更新物料');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('part:delete', '删除物料');
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('category:manage', '物料分类管理');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMIN'
  AND p.perm_code IN ('part:create', 'part:update', 'part:delete', 'category:manage');
