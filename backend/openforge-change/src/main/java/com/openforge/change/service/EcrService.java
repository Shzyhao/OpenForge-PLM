package com.openforge.change.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.change.client.MaterialClient;
import com.openforge.change.client.NumberClient;
import com.openforge.change.client.WorkflowClient;
import com.openforge.change.dto.EcrDetailResponse;
import com.openforge.change.dto.EcrRequest;
import com.openforge.change.dto.PageResponse;
import com.openforge.change.entity.ChangeRequest;
import com.openforge.change.mapper.ChangeRequestMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ECR 变更申请（开发文档 3.3）：创建即绑定 ecr-review 流程，审批状态实时关联流程实例。
 * 刀2 类型化：SUBSTITUTE_CHANGE（发布版 BOM 替代组调整）/ PART_STATE_CHANGE（物料禁用启用），
 * 审批通过（流程终态惰性回流）后同步执行 apply（决策 D6），失败可重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrService {

    static final String NUMBER_RULE_KEY = "ecr";
    static final String FLOW_KEY = "ecr-review";

    static final String TYPE_GENERIC = "GENERIC";
    static final String TYPE_SUBSTITUTE = "SUBSTITUTE_CHANGE";
    static final String TYPE_PART_STATE = "PART_STATE_CHANGE";

    private final ChangeRequestMapper mapper;
    private final NumberClient numberClient;
    private final WorkflowClient workflowClient;
    private final MaterialClient materialClient;
    private final com.openforge.common.event.EventPublisher eventPublisher;
    /** Spring 配置的 ObjectMapper（JavaTimeModule） */
    private final ObjectMapper objectMapper;

    @Transactional
    public ChangeRequest create(EcrRequest request, Long initiatorId) {
        String changeType = request.getChangeType() == null || request.getChangeType().isBlank()
                ? TYPE_GENERIC : request.getChangeType();
        String payload = switch (changeType) {
            case TYPE_GENERIC -> null;
            case TYPE_SUBSTITUTE -> buildSubstitutePayload(request.getPayload());
            case TYPE_PART_STATE -> buildPartStatePayload(request.getPayload());
            default -> throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知变更类型: " + changeType);
        };

        ChangeRequest ecr = new ChangeRequest();
        ecr.setEcrNumber(numberClient.next(NUMBER_RULE_KEY));
        ecr.setTitle(request.getTitle());
        ecr.setReason(request.getReason());
        ecr.setUrgency(request.getUrgency() == null ? "NORMAL" : request.getUrgency());
        ecr.setAffectedItems(request.getAffectedItems());
        ecr.setState("SUBMITTED");
        ecr.setChangeType(changeType);
        ecr.setPayload(payload);
        ecr.setApplyState(TYPE_GENERIC.equals(changeType) ? null : "PENDING");
        ecr.setInitiatorId(initiatorId);
        ecr.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        mapper.insert(ecr);

        Long instanceId = workflowClient.start(FLOW_KEY, "ECR", ecr.getId(),
                Map.of("title", request.getTitle(), "urgency", ecr.getUrgency()));
        ecr.setWorkflowInstanceId(instanceId);
        mapper.updateById(ecr);
        log.info("ECR created: {} type={} flow={}", ecr.getEcrNumber(), changeType, instanceId);
        return ecr;
    }

    /** 详情：实时关联流程实例状态（流程服务不可用时降级仅展示 ECR 状态）。 */
    public EcrDetailResponse detail(Long id) {
        ChangeRequest ecr = require(id);
        EcrDetailResponse resp = toDetail(ecr);

        WorkflowClient.InstanceView instance = workflowClient.findByBiz("ECR", ecr.getId());
        if (instance != null) {
            resp.setFlowState(instance.state());
            resp.setFlowCurrentNode(instance.currentNode());
            // 流程终态同步 ECR 状态（COMPLETED→APPROVED / REJECTED→REJECTED，查询时惰性回流）
            if ("SUBMITTED".equals(ecr.getState())) {
                if ("COMPLETED".equals(instance.state())) {
                    ecr.setState("APPROVED");
                    if (!TYPE_GENERIC.equals(ecr.getChangeType())) {
                        executeApply(ecr); // 审批回流即执行（D6 同步应用；含 updateById）
                    } else {
                        mapper.updateById(ecr);
                    }
                    emitClosed(ecr);
                } else if ("REJECTED".equals(instance.state())) {
                    ecr.setState("REJECTED");
                    mapper.updateById(ecr);
                    emitClosed(ecr);
                }
                resp.setState(ecr.getState());
                resp.setApplyState(ecr.getApplyState());
                resp.setApplyResult(ecr.getApplyResult());
            }
        }
        return resp;
    }

    /** FAILED/PENDING 变更单人工重试执行（change:retry-apply，默认审批人）。 */
    public ChangeRequest retryApply(Long id) {
        ChangeRequest ecr = require(id);
        if (!"APPROVED".equals(ecr.getState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "变更单未审批通过，无法执行");
        }
        if (TYPE_GENERIC.equals(ecr.getChangeType())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "通用 ECR 无执行动作");
        }
        if ("APPLIED".equals(ecr.getApplyState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "变更已执行成功，无需重试");
        }
        executeApply(ecr);
        return require(id);
    }

    public PageResponse<ChangeRequest> page(long page, long pageSize, String title, String changeType) {
        LambdaQueryWrapper<ChangeRequest> wrapper = new LambdaQueryWrapper<ChangeRequest>()
                .orderByDesc(ChangeRequest::getId);
        if (title != null && !title.isBlank()) {
            wrapper.like(ChangeRequest::getTitle, title.trim());
        }
        if (changeType != null && !changeType.isBlank()) {
            wrapper.eq(ChangeRequest::getChangeType, changeType);
        }
        Page<ChangeRequest> result = mapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** ECR 统计（报表）：by=state（默认）/ type。 */
    public java.util.Map<String, Long> stats(String by) {
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<ChangeRequest, String> column =
                "type".equals(by) ? ChangeRequest::getChangeType : ChangeRequest::getState;
        return mapper.selectList(new LambdaQueryWrapper<ChangeRequest>().select(column))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        column, java.util.stream.Collectors.counting()));
    }

    // ===== 类型化 payload 组装（提交期：服务端权威快照/影响清单，决策 D2/D3） =====

    private String buildSubstitutePayload(String raw) {
        JsonNode p = parsePayload(raw);
        long bomId = longField(p, "bomId", "bomId");
        long lineId = longField(p, "lineId", "lineId");
        MaterialClient.BomView bom = materialClient.bom(bomId);
        if (!"RELEASED".equals(bom.lifecycleState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "仅已发布 BOM 的替代组调整走变更（草稿请直接编辑）");
        }
        MaterialClient.LineView line = materialClient.line(lineId);
        if (!line.bomId().equals(bomId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "该行不属于所选 BOM");
        }
        List<MaterialClient.SubstituteInput> after = parseAfter(p);
        // before 以服务端当前替代组为准（审计快照不信任前端）；前后快照均富化件号/名称（审计可读）
        List<MaterialClient.SubstituteInput> before = materialClient.substitutes(bomId, lineId);
        Map<String, Object> payload = Map.of(
                "bomId", bomId, "bomNumber", bom.bomNumber(), "version", bom.version(),
                "lineId", lineId, "position", line.position() == null ? 0 : line.position(),
                "mainPartId", line.childPartId(), "mainPartNumber", line.childPartNumber(),
                "mainPartName", line.childPartName(),
                "before", enrichSubstitutes(before), "after", enrichSubstitutes(after));
        return writePayload(payload);
    }

    /** 快照项富化件号/名称（跨服务小批量 N+1，变更单低频可接受）。 */
    private List<Map<String, Object>> enrichSubstitutes(List<MaterialClient.SubstituteInput> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (MaterialClient.SubstituteInput item : items) {
            MaterialClient.PartView part = materialClient.part(item.substitutePartId());
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("substitutePartId", item.substitutePartId());
            row.put("partNumber", part.partNumber());
            row.put("name", part.name());
            row.put("priority", item.priority() == null ? 1 : item.priority());
            row.put("qtyCoefficient", item.qtyCoefficient() == null
                    ? java.math.BigDecimal.ONE : item.qtyCoefficient());
            out.add(row);
        }
        return out;
    }

    private List<MaterialClient.SubstituteInput> parseAfter(JsonNode p) {
        JsonNode afterNode = p.path("after");
        if (!afterNode.isArray()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "替代变更需提供 after 替代组数组");
        }
        List<MaterialClient.SubstituteInput> after = new ArrayList<>();
        for (JsonNode n : afterNode) {
            if (n.path("substitutePartId").asLong() <= 0) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "after 项缺少 substitutePartId");
            }
            after.add(new MaterialClient.SubstituteInput(
                    n.path("substitutePartId").asLong(),
                    n.hasNonNull("priority") ? n.path("priority").asInt() : null,
                    n.hasNonNull("qtyCoefficient") ? n.path("qtyCoefficient").decimalValue() : null));
        }
        return after;
    }

    private String buildPartStatePayload(String raw) {
        JsonNode p = parsePayload(raw);
        long partId = longField(p, "partId", "partId");
        String target = p.path("targetState").asText("");
        if (!"FROZEN".equals(target) && !"RELEASED".equals(target)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "目标状态仅支持 禁用(FROZEN)/启用(RELEASED)");
        }
        MaterialClient.PartView part = materialClient.part(partId);
        String from = part.lifecycleState();
        boolean allowed = ("FROZEN".equals(target) && "RELEASED".equals(from))
                || ("RELEASED".equals(target) && "FROZEN".equals(from));
        if (!allowed) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "物料当前状态 " + from + " 不允许流转到 " + target + "（禁用=RELEASED→FROZEN，启用=FROZEN→RELEASED）");
        }
        // 禁用前影响清单快照（决策 D3：不阻断存量引用，但证据入册）
        List<Map<String, Object>> whereUsed = materialClient.whereUsed(partId);
        Map<String, Object> payload = Map.of(
                "partId", partId, "partNumber", part.partNumber(), "partName", part.name(),
                "fromState", from, "targetState", target,
                "whereUsed", whereUsed);
        return writePayload(payload);
    }

    // ===== 审批后执行（Apply 分发，决策 D6） =====

    private void executeApply(ChangeRequest ecr) {
        try {
            String result;
            if (TYPE_SUBSTITUTE.equals(ecr.getChangeType())) {
                JsonNode p = parsePayload(ecr.getPayload());
                List<MaterialClient.SubstituteInput> after = parseAfter(p);
                materialClient.applySubstitutes(p.path("lineId").asLong(), after, ecr.getId());
                result = "替代组已应用（" + after.size() + " 项）";
            } else if (TYPE_PART_STATE.equals(ecr.getChangeType())) {
                JsonNode p = parsePayload(ecr.getPayload());
                MaterialClient.PartView part = materialClient.applyPartLifecycle(
                        p.path("partId").asLong(), p.path("targetState").asText());
                result = "物料 " + part.partNumber() + " 状态已流转至 " + part.lifecycleState();
            } else {
                return;
            }
            ecr.setApplyState("APPLIED");
            ecr.setApplyResult(result);
            log.info("change applied: ecrId={} type={} result={}", ecr.getId(), ecr.getChangeType(), result);
        } catch (Exception e) {
            // 失败不回滚审批状态：apply_state=FAILED + 原因落库，人工重试（设计 §5.4）
            ecr.setApplyState("FAILED");
            ecr.setApplyResult(e.getMessage());
            log.error("change apply failed: ecrId={} type={}", ecr.getId(), ecr.getChangeType(), e);
        }
        mapper.updateById(ecr);
    }

    // ===== 私有辅助 =====

    private EcrDetailResponse toDetail(ChangeRequest ecr) {
        EcrDetailResponse resp = new EcrDetailResponse();
        resp.setId(ecr.getId());
        resp.setEcrNumber(ecr.getEcrNumber());
        resp.setTitle(ecr.getTitle());
        resp.setReason(ecr.getReason());
        resp.setUrgency(ecr.getUrgency());
        resp.setAffectedItems(ecr.getAffectedItems());
        resp.setState(ecr.getState());
        resp.setChangeType(ecr.getChangeType());
        resp.setPayload(ecr.getPayload());
        resp.setApplyState(ecr.getApplyState());
        resp.setApplyResult(ecr.getApplyResult());
        resp.setWorkflowInstanceId(ecr.getWorkflowInstanceId());
        resp.setCreatedAt(ecr.getCreatedAt());
        return resp;
    }

    private ChangeRequest require(Long id) {
        ChangeRequest ecr = mapper.selectById(id);
        if (ecr == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "变更申请不存在");
        }
        return ecr;
    }

    private JsonNode parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少类型化 payload");
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "payload JSON 不合法: " + e.getMessage());
        }
    }

    private long longField(JsonNode p, String field, String label) {
        long v = p.path(field).asLong(0);
        if (v <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "payload 缺少 " + label);
        }
        return v;
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "payload 序列化失败");
        }
    }

    /** change.closed（B2 事件清单：knowledge 案例沉淀/project 预留消费）。 */
    private void emitClosed(ChangeRequest ecr) {
        eventPublisher.publish("openforge-change", "change.closed", java.util.Map.of(
                "ecrId", ecr.getId(), "ecrNumber", ecr.getEcrNumber(), "finalState", ecr.getState()));
    }
}
