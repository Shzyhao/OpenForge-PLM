-- 刀2（替代件与主数据变更专项，设计文档 §4.2）：变更单类型化 + 审批后执行状态
ALTER TABLE change_request ADD COLUMN change_type  VARCHAR(20) NOT NULL DEFAULT 'GENERIC';
ALTER TABLE change_request ADD COLUMN payload      TEXT;   -- 类型化明细 JSON（含前后快照）
ALTER TABLE change_request ADD COLUMN apply_state  VARCHAR(10);  -- PENDING/APPLIED/FAILED，仅可执行类型
ALTER TABLE change_request ADD COLUMN apply_result TEXT;   -- 执行结果 / 失败原因
