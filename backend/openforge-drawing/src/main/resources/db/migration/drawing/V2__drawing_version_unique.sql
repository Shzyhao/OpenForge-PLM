-- 2026-11-28 审查修复（#批二 并发遗漏）：drw_drawing_version 无唯一约束——approve 原无条件更新，
-- 双击并发产出双快照双事件。approve 已改 CAS 条件流转，本约束作持久层兜底。
-- 既有库若已存在重复 (drawing_id, version) 行，先去重（保留 id 最小）再建唯一索引（H2/PG 兼容）。
DELETE FROM drw_drawing_version v1
WHERE EXISTS (
    SELECT 1 FROM drw_drawing_version v2
    WHERE v2.drawing_id = v1.drawing_id AND v2.version = v1.version AND v2.id < v1.id
);
CREATE UNIQUE INDEX uk_drw_version_drawing_version ON drw_drawing_version (drawing_id, version);
