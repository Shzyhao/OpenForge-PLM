-- 2026-11-28 审查修复（#4 全新库 ECR 审批无人可办）：V2 种子 ecr-review 的审批节点写的是角色名 'ADMIN'，
-- 而 auth V14 已将其更名为 'ADMINS'（V14 与本模块迁移互不可见）——全新库按此定义生成的待办
-- candidate_role='ADMIN' 无人持有，审批永远无人可办（dev 库靠手工重部署 v2 才恢复）。
-- 修法：流程定义 JSON 与**悬挂中的待办**一并按新角色名修正（只动未办理行，已办历史不改写）。

-- 1) 流程定义 JSON 内的审批角色修正（含 ecr-review 全版本；业务自建定义若同样引用 ADMIN 一并修正）
UPDATE workflow_def
SET definition = REPLACE(definition, '"value": "ADMIN"', '"value": "ADMINS"')
WHERE definition LIKE '%"value": "ADMIN"%';

-- 2) 悬挂待办（未办理）的候选角色修正；已办理历史行不改写
UPDATE workflow_task
SET candidate_role = 'ADMINS'
WHERE candidate_role = 'ADMIN' AND action IS NULL;
