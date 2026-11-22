-- ECR 联动单幂等键（v1.31 P1-4）：drawing.released 联动幂等原为 selectCount-then-insert，
-- 双通道（MQ+HTTP 回退）/重投并发时可建两条同 title 联动单各起审批流。
-- 唯一性只约束联动单（link_key 非空行）——人工 ECR 同 title 是合法场景，不得限制；
-- UNIQUE 对 NULL 不生效（H2/PG 一致），人工单 link_key 恒 NULL。
ALTER TABLE change_request ADD COLUMN link_key VARCHAR(255);
ALTER TABLE change_request
    ADD CONSTRAINT uk_change_request_link_key UNIQUE (tenant_id, link_key);
