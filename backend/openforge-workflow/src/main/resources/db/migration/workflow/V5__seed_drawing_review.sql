-- 内置图纸审签流程（v1.25 设计 §6）：单节点 ENGINEER 角色评审；业务可随时部署新版覆盖
-- 与 ecr-review 同构（角色认领）；驳回无 rejectTo 即终止实例（REJECTED）→ drawing 惰性回流打回 DRAFT
INSERT INTO workflow_def (def_key, name, version, status, definition, tenant_id)
VALUES ('drawing-review', '图纸审签', 1, 'PUBLISHED',
'{"nodes": [
   {"id": "start", "type": "START"},
   {"id": "review", "type": "APPROVAL", "name": "图纸评审", "assignee": {"type": "ROLE", "value": "ENGINEER"}},
   {"id": "end", "type": "END"}
 ],
 "edges": [{"from": "start", "to": "review"}, {"from": "review", "to": "end"}]}', 0);
