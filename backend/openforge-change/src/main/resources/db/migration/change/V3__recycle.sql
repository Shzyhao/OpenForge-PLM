-- 回收站接入（v1.25 设计 §4）：change_request 软删列。SUBMITTED（审批在途）不可删——
-- 流程实例仍在运行，删除会造成 workflow_instance 悬挂引用
ALTER TABLE change_request ADD COLUMN deleted SMALLINT NOT NULL DEFAULT 0;
