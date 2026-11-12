-- 回收站接入（v1.25 设计 §4）：project 软删列；purge 连带 project_task GC
ALTER TABLE project ADD COLUMN deleted SMALLINT NOT NULL DEFAULT 0;
