-- M2 BOM 编号规则（B-yyyyMMdd-4位流水, 日重置）
INSERT INTO sys_number_rule (rule_key, rule_name, segments, reset_policy)
VALUES ('bom', 'BOM编码', '[{"type":"CONST","value":"B"},{"type":"DATE","pattern":"yyyyMMdd"},{"type":"SEQ","length":4}]', 'DAILY');
