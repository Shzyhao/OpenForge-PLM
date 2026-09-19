-- 连接器调用白名单 ACL（十轮排查改进项 Q2 提前）：
-- acl_roles 为角色代码 JSON 数组（如 ["ADMINS","ENGINEER"]），NULL/空 = 租户内持有 conn:invoke 权限者皆可调用；
-- 仅约束运行时 invoke（/invoke/{connCode}），设计态 test 走 conn:manage、服务间内部调用走内部令牌，均不受限。
ALTER TABLE conn_definition ADD COLUMN acl_roles TEXT;
