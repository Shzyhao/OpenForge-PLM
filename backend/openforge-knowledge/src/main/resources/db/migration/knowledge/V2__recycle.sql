-- 回收站接入（v1.25 设计 §4）：knowledge_item 软删列（对齐 part/doc/drawing 的 @TableLogic 模式）
ALTER TABLE knowledge_item ADD COLUMN deleted SMALLINT NOT NULL DEFAULT 0;
