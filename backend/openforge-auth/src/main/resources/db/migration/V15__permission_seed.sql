-- 权限点全量 seed（方案 C2）：菜单权限 + 各模块 view 门槛
-- 说明：现有操作权限点（part:create 等）保留原编码不动，避免注解引用漂移；本迁移补菜单层与 view 层

-- 菜单权限（MENU）
INSERT INTO sys_permission (perm_code, perm_name, perm_type, sort_order) VALUES
('menu:dashboard',  '工作台',   'MENU', 1),
('menu:tasks',      '我的待办', 'MENU', 2),
('menu:material',   '物料',     'MENU', 3),
('menu:bom',        'BOM',      'MENU', 4),
('menu:doc',        '文档',     'MENU', 5),
('menu:change',     '变更',     'MENU', 6),
('menu:workflow',   '流程',     'MENU', 7),
('menu:knowledge',  '知识库',   'MENU', 8),
('menu:project',    '项目',     'MENU', 9),
('menu:system',     '系统管理', 'MENU', 10);

-- 各模块 view 门槛（OPERATION；校验逻辑随 P-3 上线，数据先行）
INSERT INTO sys_permission (perm_code, perm_name, perm_type, sort_order) VALUES
('dashboard:view',  '工作台查看',   'OPERATION', 1),
('material:view',   '物料查看',     'OPERATION', 2),
('bom:view',        'BOM查看',      'OPERATION', 3),
('doc:view',        '文档查看',     'OPERATION', 4),
('change:view',     '变更查看',     'OPERATION', 5),
('workflow:view',   '流程查看',     'OPERATION', 6),
('knowledge:view',  '知识库查看',   'OPERATION', 7),
('project:view',    '项目查看',     'OPERATION', 8),
('system:view',     '系统管理查看', 'OPERATION', 9);

-- ADMINS 绑定全部权限点（现有+新增；WHERE NOT EXISTS 幂等，兼容 H2/PG）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'ADMINS'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- 内置业务角色默认权限：ENGINEER/VIEWER 授予全部菜单 + 各模块 view（只读基线；细粒度由管理员调整）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code IN ('ENGINEER', 'VIEWER') AND p.perm_type = 'MENU'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code IN ('ENGINEER', 'VIEWER')
  AND p.perm_code IN ('dashboard:view','material:view','bom:view','doc:view','change:view','workflow:view','knowledge:view','project:view')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
