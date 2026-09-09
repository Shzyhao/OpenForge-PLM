-- P3 多步骤编排刀1（集成编排器 MVP 设计 §14）：链执行步骤摘要。
-- 一次链执行一条主日志；steps_json 记录每步 key/status/duration/error 摘要（脱敏同主日志）。
ALTER TABLE conn_exec_log ADD COLUMN steps_json TEXT;
