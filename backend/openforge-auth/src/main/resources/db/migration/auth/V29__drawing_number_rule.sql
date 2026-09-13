-- M 图纸编号规则（DW-yyyyMMdd-4位流水, 日重置；对齐 V9 文档规则模式）
INSERT INTO sys_number_rule (rule_key, rule_name, segments, reset_policy)
VALUES ('drawing', '图纸编号', '[{"type":"CONST","value":"DW"},{"type":"DATE","pattern":"yyyyMMdd"},{"type":"SEQ","length":4}]', 'DAILY');
