package com.openforge.change.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.util.Map;

/** ECR 变更申请（开发文档 3.3）：创建即绑定 ecr-review 流程，审批状态实时关联流程实例。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrService {

    static final String NUMBER_RULE_KEY = "ecr";
    static final String FLOW_KEY = "ecr-review";

    private final ChangeRequestMapper mapper;
    private final NumberClient numberClient;
    private final WorkflowClient workflowClient;
    private final com.openforge.common.event.EventPublisher eventPublisher;

    @Transactional
    public ChangeRequest create(EcrRequest request, Long initiatorId) {
        ChangeRequest ecr = new ChangeRequest();
        ecr.setEcrNumber(numberClient.next(NUMBER_RULE_KEY));
        ecr.setTitle(request.getTitle());
        ecr.setReason(request.getReason());
        ecr.setUrgency(request.getUrgency() == null ? "NORMAL" : request.getUrgency());
        ecr.setAffectedItems(request.getAffectedItems());
        ecr.setState("SUBMITTED");
        ecr.setInitiatorId(initiatorId);
        ecr.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        mapper.insert(ecr);

        Long instanceId = workflowClient.start(FLOW_KEY, "ECR", ecr.getId(),
                Map.of("title", request.getTitle(), "urgency", ecr.getUrgency()));
        ecr.setWorkflowInstanceId(instanceId);
        mapper.updateById(ecr);
        log.info("ECR created: {} flow={}", ecr.getEcrNumber(), instanceId);
        return ecr;
    }

    /** 详情：实时关联流程实例状态（流程服务不可用时降级仅展示 ECR 状态）。 */
    public EcrDetailResponse detail(Long id) {
        ChangeRequest ecr = require(id);
        EcrDetailResponse resp = new EcrDetailResponse();
        resp.setId(ecr.getId());
        resp.setEcrNumber(ecr.getEcrNumber());
        resp.setTitle(ecr.getTitle());
        resp.setReason(ecr.getReason());
        resp.setUrgency(ecr.getUrgency());
        resp.setAffectedItems(ecr.getAffectedItems());
        resp.setState(ecr.getState());
        resp.setWorkflowInstanceId(ecr.getWorkflowInstanceId());
        resp.setCreatedAt(ecr.getCreatedAt());

        WorkflowClient.InstanceView instance = workflowClient.findByBiz("ECR", ecr.getId());
        if (instance != null) {
            resp.setFlowState(instance.state());
            resp.setFlowCurrentNode(instance.currentNode());
            // 流程终态同步 ECR 状态（COMPLETED→APPROVED / REJECTED→REJECTED，查询时惰性回流）
            if ("SUBMITTED".equals(ecr.getState())) {
                if ("COMPLETED".equals(instance.state())) {
                    ecr.setState("APPROVED");
                    mapper.updateById(ecr);
                    emitClosed(ecr);
                } else if ("REJECTED".equals(instance.state())) {
                    ecr.setState("REJECTED");
                    mapper.updateById(ecr);
                    emitClosed(ecr);
                }
                resp.setState(ecr.getState());
            }
        }
        return resp;
    }

    public PageResponse<ChangeRequest> page(long page, long pageSize, String title) {
        LambdaQueryWrapper<ChangeRequest> wrapper = new LambdaQueryWrapper<ChangeRequest>()
                .orderByDesc(ChangeRequest::getId);
        if (title != null && !title.isBlank()) {
            wrapper.like(ChangeRequest::getTitle, title.trim());
        }
        Page<ChangeRequest> result = mapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** ECR 状态分布统计（报表）。 */
    public java.util.Map<String, Long> stats() {
        return mapper.selectList(new LambdaQueryWrapper<ChangeRequest>().select(ChangeRequest::getState))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ChangeRequest::getState, java.util.stream.Collectors.counting()));
    }

    private ChangeRequest require(Long id) {
        ChangeRequest ecr = mapper.selectById(id);
        if (ecr == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "变更申请不存在");
        }
        return ecr;
    }

    /** change.closed（B2 事件清单：knowledge 案例沉淀/project 预留消费）。 */
    private void emitClosed(ChangeRequest ecr) {
        eventPublisher.publish("openforge-change", "change.closed", java.util.Map.of(
                "ecrId", ecr.getId(), "ecrNumber", ecr.getEcrNumber(), "finalState", ecr.getState()));
    }
}
