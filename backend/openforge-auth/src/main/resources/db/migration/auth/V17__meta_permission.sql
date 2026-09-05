-- F2 元数据建模权限点 + ADMIN 绑定
-- 动态对象四权限点（{objectKey}:view/create/update/delete）随发布流水线按对象创建（F2-3）
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('meta:manage', '元数据建模管理');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMIN' AND p.perm_code = 'meta:manage';
