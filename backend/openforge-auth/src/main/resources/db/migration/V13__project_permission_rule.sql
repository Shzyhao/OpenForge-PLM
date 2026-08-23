-- M6 项目权限点 + 编号规则
INSERT INTO sys_permission (perm_code, perm_name) VALUES ('project:manage', '项目管理');
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMIN' AND p.perm_code = 'project:manage';

INSERT INTO sys_number_rule (rule_key, rule_name, segments, reset_policy)
VALUES ('project', '项目编号', '[{"type":"CONST","value":"PRJ"},{"type":"DATE","pattern":"yyyyMMdd"},{"type":"SEQ","length":4}]', 'DAILY');
