-- M2 文档编号规则（D-yyyyMMdd-4位流水, 日重置）
INSERT INTO sys_number_rule (rule_key, rule_name, segments, reset_policy)
VALUES ('doc', '文档编号', '[{"type":"CONST","value":"D"},{"type":"DATE","pattern":"yyyyMMdd"},{"type":"SEQ","length":4}]', 'DAILY');
