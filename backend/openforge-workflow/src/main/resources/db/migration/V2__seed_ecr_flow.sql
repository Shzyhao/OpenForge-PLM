-- 内置 ECR 评审流程（单节点 ADMIN 角色审批；业务可随时部署新版覆盖）
INSERT INTO workflow_def (def_key, name, version, status, definition, tenant_id)
VALUES ('ecr-review', 'ECR变更评审', 1, 'PUBLISHED',
'{"nodes": [
   {"id": "start", "type": "START"},
   {"id": "review", "type": "APPROVAL", "name": "变更评审", "assignee": {"type": "ROLE", "value": "ADMIN"}},
   {"id": "end", "type": "END"}
 ],
 "edges": [{"from": "start", "to": "review"}, {"from": "review", "to": "end"}]}', 0);
