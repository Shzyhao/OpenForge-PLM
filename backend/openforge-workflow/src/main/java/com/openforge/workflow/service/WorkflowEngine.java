package com.openforge.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.security.PermissionQueryClient;
import com.openforge.common.spel.ExpressionEvaluator;
import com.openforge.workflow.engine.ProcessDefinition;
import com.openforge.workflow.entity.WorkflowDef;
import com.openforge.workflow.entity.WorkflowDelegate;
import com.openforge.workflow.entity.WorkflowInstance;
import com.openforge.workflow.entity.WorkflowTask;
import com.openforge.workflow.mapper.WorkflowDefMapper;
import com.openforge.workflow.mapper.WorkflowInstanceMapper;
import com.openforge.workflow.mapper.WorkflowTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程引擎内核（开发文档第 6 章）：
 * 部署（版本化）→ 启动（定义快照）→ 自动推进（START/CONDITION/END）→ 审批节点生成任务 → 办理推进。
 * M3-1 为顺序/条件/审批；会签、加签、并行网关随 M3-2 迭代。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowDefMapper defMapper;
    private final WorkflowInstanceMapper instanceMapper;
    private final WorkflowTaskMapper taskMapper;
    private final ExpressionEvaluator evaluator;
    private final PermissionQueryClient permissionQueryClient;
    private final ObjectMapper objectMapper;
    private final com.openforge.common.event.EventPublisher eventPublisher;
    private final DelegateService delegateService;
    private final com.openforge.workflow.client.NotifyClient notifyClient;

    // ===== 定义 =====

    @Transactional
    public WorkflowDef deploy(String defKey, String name, String definitionJson, Long operatorId) {
        ProcessDefinition definition = parse(definitionJson);
        definition.validate();
        Integer maxVersion = defMapper.selectList(new LambdaQueryWrapper<WorkflowDef>()
                        .eq(WorkflowDef::getDefKey, defKey)
                        .orderByDesc(WorkflowDef::getVersion).last("LIMIT 1"))
                .stream().findFirst().map(WorkflowDef::getVersion).orElse(0);
        WorkflowDef def = new WorkflowDef();
        def.setDefKey(defKey);
        def.setName(name);
        def.setVersion(maxVersion + 1);
        def.setStatus("PUBLISHED");
        def.setDefinition(definitionJson);
        def.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        def.setCreatedBy(operatorId);
        defMapper.insert(def);
        return def;
    }

    public WorkflowDef latestDef(String defKey) {
        return defMapper.selectList(new LambdaQueryWrapper<WorkflowDef>()
                        .eq(WorkflowDef::getDefKey, defKey)
                        .eq(WorkflowDef::getStatus, "PUBLISHED")
                        .orderByDesc(WorkflowDef::getVersion).last("LIMIT 1"))
                .stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "流程定义不存在: " + defKey));
    }

    /** 全部定义（按 key+版本倒序）。 */
    public List<WorkflowDef> listDefs() {
        return defMapper.selectList(new LambdaQueryWrapper<WorkflowDef>()
                .orderByAsc(WorkflowDef::getDefKey).orderByDesc(WorkflowDef::getVersion));
    }

    // ===== 实例 =====

    @Transactional
    public WorkflowInstance start(String defKey, String bizType, Long bizId,
                                  Map<String, Object> variables, Long initiatorId) {
        WorkflowDef def = latestDef(defKey);
        WorkflowInstance instance = new WorkflowInstance();
        instance.setDefKey(def.getDefKey());
        instance.setDefVersion(def.getVersion());
        instance.setDefSnapshot(def.getDefinition()); // 快照：在途实例与新版定义解耦
        instance.setBizType(bizType);
        instance.setBizId(bizId);
        instance.setVariables(toJson(variables == null ? Map.of() : variables));
        instance.setState("RUNNING");
        instance.setInitiatorId(initiatorId);
        instance.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        instanceMapper.insert(instance);
        advance(instance, "start", variables == null ? Map.of() : variables);
        return instance;
    }

    public WorkflowInstance instance(Long id) {
        WorkflowInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "流程实例不存在");
        }
        // 租户边界（R10）：实例为全局表，跨租户按不存在应答（与任务 act 的指派人校验互补）
        Long tenantId = com.openforge.common.tenant.TenantContext.getTenantId();
        if (tenantId != null && tenantId != 0L && !tenantId.equals(instance.getTenantId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "流程实例不存在");
        }
        return instance;
    }

    /** 按业务对象查最新实例（含终态——业务详情需展示 COMPLETED/REJECTED；冒烟暴露只查 RUNNING 导致终态不可见）。 */
    public WorkflowInstance findByBiz(String bizType, Long bizId) {
        LambdaQueryWrapper<WorkflowInstance> wrapper = new LambdaQueryWrapper<WorkflowInstance>()
                .eq(WorkflowInstance::getBizType, bizType)
                .eq(WorkflowInstance::getBizId, bizId);
        // 租户边界（R10）：内部调用方（change 等）经 X-User-Tenant 透传本租户；平台租户(0)不限
        Long tenantId = com.openforge.common.tenant.TenantContext.getTenantId();
        if (tenantId != null && tenantId != 0L) {
            wrapper.eq(WorkflowInstance::getTenantId, tenantId);
        }
        return instanceMapper.selectOne(wrapper
                .orderByDesc(WorkflowInstance::getId)
                .last("LIMIT 1"));
    }

    /** 实例状态分布统计（报表）。 */
    public Map<String, Long> instanceStats() {
        return instanceMapper.selectList(new LambdaQueryWrapper<WorkflowInstance>()
                        .select(WorkflowInstance::getState))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        WorkflowInstance::getState, java.util.stream.Collectors.counting()));
    }

    // ===== 任务 =====

    /** 我的待办：直接指派给我的 + 我的角色可认领的 + 生效委托规则带来的委托人任务（v1.23）。 */
    public List<WorkflowTask> myTasks(Long userId) {
        List<String> roles = permissionQueryClient.fetch(userId).roles();
        List<WorkflowDelegate> delegations = delegateService.activeForAgent(userId);
        List<Long> principals = delegations.stream()
                .map(WorkflowDelegate::getPrincipalId)
                .filter(pid -> !pid.equals(userId))
                .distinct()
                .toList();
        LambdaQueryWrapper<WorkflowTask> wrapper = new LambdaQueryWrapper<WorkflowTask>()
                .isNull(WorkflowTask::getAction)
                .orderByDesc(WorkflowTask::getId)
                .and(w -> {
                    w.eq(WorkflowTask::getAssigneeId, userId);
                    if (!roles.isEmpty()) {
                        w.or().in(WorkflowTask::getCandidateRole, roles);
                    }
                    if (!principals.isEmpty()) {
                        w.or().in(WorkflowTask::getAssigneeId, principals);
                    }
                });
        List<WorkflowTask> tasks = taskMapper.selectList(wrapper);
        if (principals.isEmpty()) {
            return tasks;
        }
        // def_key 范围过滤 + 委托来源标记（瞬态字段，前端据此打"来自委托"标）
        Map<Long, String> defKeyByInstance = instanceDefKeys(tasks);
        List<WorkflowTask> visible = new java.util.ArrayList<>(tasks.size());
        for (WorkflowTask t : tasks) {
            if (t.getAssigneeId() == null || !principals.contains(t.getAssigneeId())) {
                visible.add(t);
                continue;
            }
            boolean covered = delegations.stream().anyMatch(r -> t.getAssigneeId().equals(r.getPrincipalId())
                    && (r.getDefKey() == null || r.getDefKey().equals(defKeyByInstance.get(t.getInstanceId()))));
            if (covered) {
                t.setViaDelegation(true);
                visible.add(t);
            }
        }
        return visible;
    }

    private Map<Long, String> instanceDefKeys(List<WorkflowTask> tasks) {
        List<Long> instanceIds = tasks.stream()
                .map(WorkflowTask::getInstanceId).filter(java.util.Objects::nonNull).distinct().toList();
        if (instanceIds.isEmpty()) {
            return Map.of();
        }
        return instanceMapper.selectBatchIds(instanceIds).stream()
                .collect(java.util.stream.Collectors.toMap(WorkflowInstance::getId,
                        i -> i.getDefKey() == null ? "" : i.getDefKey()));
    }

    /** 办理任务。APPROVE：ALL 会签需全票通过才推进，ANY 或签一人即决定；REJECT：按 rejectTo 回退或终止。 */
    @Transactional
    public WorkflowInstance act(Long taskId, Long userId, String action, String comment) {
        WorkflowTask task = taskMapper.selectById(taskId);
        if (task == null || !task.isOpen()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或已办理");
        }
        // 动作归一化 + 校验（v1.20.0 体检修复）：此前大小写敏感——小写 "approve" 会让
        // 会签判定 "APPROVE".equals(action) 恒 false，实例在当前节点静默挂起（无任何报错）
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if (!"APPROVE".equals(normalized) && !"REJECT".equals(normalized)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "action 须为 APPROVE/REJECT: " + action);
        }
        boolean assignedToMe = userId.equals(task.getAssigneeId());
        boolean myRole = task.getCandidateRole() != null
                && permissionQueryClient.fetch(userId).roles().contains(task.getCandidateRole());
        // 委托代办（v1.23）：仅 USER 指派任务——角色任务本就可被角色成员认领，不重复放行；
        // defKey 用裸查实例（不经租户守卫，规则本身已按租户过滤，且不改变既有校验顺序/语义）
        boolean delegatedToMe = false;
        if (!assignedToMe && !myRole && task.getAssigneeId() != null) {
            WorkflowInstance raw = instanceMapper.selectById(task.getInstanceId());
            String defKey = raw == null ? null : raw.getDefKey();
            delegatedToMe = delegateService.hasActiveDelegation(task.getAssigneeId(), userId, defKey);
        }
        if (!assignedToMe && !myRole && !delegatedToMe) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权办理该任务");
        }

        WorkflowInstance inst = instance(task.getInstanceId());
        if (delegatedToMe) {
            task.setDelegatedFrom(task.getAssigneeId()); // 代办追溯：记录原指派人
        }
        task.setAction(normalized);
        task.setComment(comment);
        task.setAssigneeId(userId); // 角色任务认领后记录办理人
        task.setActedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        emitTask("task.completed", inst, task.getNodeName(), task);

        ProcessDefinition.NodeDef node = parse(inst.getDefSnapshot()).node(task.getNodeId());
        List<WorkflowTask> nodeTasks = taskMapper.selectList(new LambdaQueryWrapper<WorkflowTask>()
                .eq(WorkflowTask::getInstanceId, inst.getId())
                .eq(WorkflowTask::getNodeId, task.getNodeId()));
        List<WorkflowTask> openTasks = nodeTasks.stream().filter(WorkflowTask::isOpen).toList();
        boolean anyMode = "ANY".equals(node.mode());
        boolean allApproved = openTasks.isEmpty()
                && nodeTasks.stream().allMatch(t -> "APPROVE".equals(t.getAction()));

        if ("REJECT".equals(normalized)) {
            cancelOpenTasks(openTasks);
            if (node.rejectTo() != null) {
                return fallBack(inst, node.rejectTo());
            }
            return terminate(inst, "REJECTED");
        }
        if (anyMode) {
            cancelOpenTasks(openTasks); // 或签：一人通过即决定
        } else if (!allApproved) {
            instanceMapper.updateById(inst); // 会签：还有未办理的，等待
            log.debug("workflow node {} waiting for remaining approvals, instance {}", node.id(), inst.getId());
            return inst;
        }
        Map<String, Object> vars = vars(inst);
        advance(inst, task.getNodeId(), vars);
        return inst;
    }

    /** 回退到指定节点：重新生成该节点任务。 */
    private WorkflowInstance fallBack(WorkflowInstance inst, String targetNodeId) {
        ProcessDefinition definition = parse(inst.getDefSnapshot());
        ProcessDefinition.NodeDef target = definition.node(targetNodeId);
        createTasks(inst, target);
        inst.setCurrentNode(target.id());
        instanceMapper.updateById(inst);
        log.info("workflow fell back to {} instance {}", target.id(), inst.getId());
        return inst;
    }

    private WorkflowInstance terminate(WorkflowInstance inst, String state) {
        inst.setState(state);
        inst.setCurrentNode(null);
        inst.setEndedAt(LocalDateTime.now());
        instanceMapper.updateById(inst);
        log.info("workflow {} : instance {}", state, inst.getId());
        return inst;
    }

    private void cancelOpenTasks(List<WorkflowTask> openTasks) {
        for (WorkflowTask t : openTasks) {
            t.setAction("CANCELLED");
            t.setComment("由节点决定自动取消");
            t.setActedAt(LocalDateTime.now());
            taskMapper.updateById(t);
        }
    }

    // ===== 推进内核 =====

    /** 从 fromNode 的出边推进：CONDITION 求值选边，APPROVAL 生成任务等待，END 完成。 */
    private void advance(WorkflowInstance instance, String fromNodeId, Map<String, Object> vars) {
        ProcessDefinition definition = parse(instance.getDefSnapshot());
        ProcessDefinition.EdgeDef edge = definition.edgeFrom(fromNodeId);
        String nextId = edge.to();

        while (true) {
            ProcessDefinition.NodeDef node = definition.node(nextId);
            switch (node.type()) {
                case "APPROVAL" -> {
                    createTasks(instance, node);
                    instance.setCurrentNode(node.id());
                    instanceMapper.updateById(instance);
                    log.debug("workflow waiting at approval node {} instance {}", node.id(), instance.getId());
                    return;
                }
                case "CONDITION" -> {
                    String matched = null;
                    for (ProcessDefinition.RuleDef rule : node.rules()) {
                        if (rule.expr() == null) {
                            continue; // 默认分支最后兜底
                        }
                        if (evaluator.evaluate(rule.expr(), vars)) {
                            matched = rule.to();
                            break;
                        }
                    }
                    if (matched == null) {
                        matched = node.rules().stream().filter(r -> r.expr() == null)
                                .findFirst().orElseThrow(() -> new BizException(
                                        ErrorCode.INTERNAL_ERROR, "条件节点缺少默认分支: " + node.id()))
                                .to();
                    }
                    nextId = matched;
                    continue;
                }
                case "END" -> {
                    instance.setState("COMPLETED");
                    instance.setCurrentNode(null);
                    instance.setEndedAt(LocalDateTime.now());
                    instanceMapper.updateById(instance);
                    log.info("workflow completed: instance={} biz={}/{}", instance.getId(),
                            instance.getBizType(), instance.getBizId());
                    return;
                }
                default -> throw new BizException(ErrorCode.INTERNAL_ERROR, "未知节点类型: " + node.type());
            }
        }
    }

    /** 按审批人配置生成任务：USER/ROLE 单条；USERS 每人一条（会签/或签由 mode 决定）。 */
    private void createTasks(WorkflowInstance instance, ProcessDefinition.NodeDef node) {
        ProcessDefinition.AssigneeDef assignee = node.assignee();
        switch (assignee.type()) {
            case "USER", "ROLE" -> {
                WorkflowTask task = new WorkflowTask();
                task.setInstanceId(instance.getId());
                task.setNodeId(node.id());
                task.setNodeName(node.name());
                if ("USER".equals(assignee.type())) {
                    task.setAssigneeId(Long.valueOf(assignee.value()));
                } else {
                    task.setCandidateRole(assignee.value());
                }
                taskMapper.insert(task);
                emitTask("task.created", instance, node.name(), task);
            }
            case "USERS" -> assignee.values().forEach(uid -> {
                WorkflowTask task = new WorkflowTask();
                task.setInstanceId(instance.getId());
                task.setNodeId(node.id());
                task.setNodeName(node.name());
                task.setAssigneeId(Long.valueOf(uid));
                taskMapper.insert(task);
                emitTask("task.created", instance, node.name(), task);
            });
            default -> throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知审批人类型: " + assignee.type());
        }
    }

    private Map<String, Object> vars(WorkflowInstance instance) {
        try {
            if (instance.getVariables() == null || instance.getVariables().isBlank()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(instance.getVariables(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private ProcessDefinition parse(String json) {
        try {
            return objectMapper.readValue(json, ProcessDefinition.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "流程定义 JSON 解析失败: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "变量序列化失败");
        }
    }

    /** task.created/completed（B2 事件清单：notify 消费组 v1.23 落地）。总线关闭时回退 auth 站内信 HTTP 通道。 */
    private void emitTask(String eventType, WorkflowInstance instance, String nodeName, WorkflowTask task) {
        Map<String, Object> payload = java.util.Map.of(
                "instanceId", instance.getId(), "bizType", instance.getBizType() == null ? "" : instance.getBizType(),
                "bizId", instance.getBizId() == null ? 0 : instance.getBizId(),
                "nodeId", task.getNodeId() == null ? "" : task.getNodeId(),
                "nodeName", nodeName == null ? "" : nodeName,
                "assigneeId", task.getAssigneeId() == null ? 0 : task.getAssigneeId(),
                "candidateRole", task.getCandidateRole() == null ? "" : task.getCandidateRole(),
                "initiatorId", instance.getInitiatorId() == null ? 0 : instance.getInitiatorId(),
                "action", task.getAction() == null ? "" : task.getAction());
        if (!eventPublisher.publish("openforge-task", eventType, payload)) {
            notifyClient.deliver(eventType, payload);
        }
    }
}
