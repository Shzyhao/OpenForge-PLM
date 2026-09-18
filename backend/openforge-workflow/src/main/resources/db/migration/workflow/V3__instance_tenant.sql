-- R10 越权排查修复：流程实例补租户维度。
-- workflow_instance 在 GLOBAL_TABLES（任务可见性按指派人/角色跨租户解析），此前实例行无租户归属，
-- 任意租户可按 ID 直读他租户实例（含 defSnapshot/variables）。补 tenant_id：写入时按 TenantContext 打戳，读取校验同租户。
-- 存量行 DEFAULT 0（平台租户），与历史行为一致。
ALTER TABLE workflow_instance ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_wfi_tenant ON workflow_instance (tenant_id);
