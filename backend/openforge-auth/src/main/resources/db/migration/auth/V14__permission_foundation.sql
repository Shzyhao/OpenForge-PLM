-- 权限体系地基（方案 P-1）：admin 固化 / ADMINS 迁移 / 密码时效字段 / 权限模型扩展
-- 兼容性：已存在冒烟环境 username=admin 的用户时升级为 SUPER，全新库则内置创建

-- 1) 用户表扩展
ALTER TABLE sys_user ADD COLUMN user_type VARCHAR(10) NOT NULL DEFAULT 'NORMAL';      -- SUPER/NORMAL
ALTER TABLE sys_user ADD COLUMN password_updated_at TIMESTAMP;
ALTER TABLE sys_user ADD COLUMN first_login_change SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN locked_until TIMESTAMP;

-- 2) 角色表扩展
ALTER TABLE sys_role ADD COLUMN description VARCHAR(255);
ALTER TABLE sys_role ADD COLUMN enabled SMALLINT NOT NULL DEFAULT 1;

-- 3) 权限点扩展（双层权限：MENU 界面 / OPERATION 操作）
ALTER TABLE sys_permission ADD COLUMN perm_type VARCHAR(10) NOT NULL DEFAULT 'OPERATION';
ALTER TABLE sys_permission ADD COLUMN parent_id BIGINT;
ALTER TABLE sys_permission ADD COLUMN description VARCHAR(255);
ALTER TABLE sys_permission ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

-- 4) ADMIN 角色演进为 ADMINS（次级管理员，全权限点绑定、不可动 admin）
UPDATE sys_role SET role_code = 'ADMINS', role_name = '管理员组', description = '次级管理员：全部权限，但不能修改 admin 账号' WHERE role_code = 'ADMIN';

-- 5) 存量数据回填
UPDATE sys_user SET password_updated_at = created_at WHERE password_updated_at IS NULL;

-- 6) 固定 admin 账号：存在则升级 SUPER；不存在则创建（密码由启动引导器生成随机值并打印日志）
UPDATE sys_user SET user_type = 'SUPER', status = 'ACTIVE', deleted = 0 WHERE username = 'admin';
INSERT INTO sys_user (username, password_hash, display_name, status, user_type, first_login_change, tenant_id, deleted)
SELECT 'admin', 'PENDING_BOOTSTRAP', 'Administrator', 'ACTIVE', 'SUPER', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');
-- 说明：全新安装走 INSERT（first_login_change=1，首登强制改密）；已有 admin 走 UPDATE 保留密码（不打扰）
