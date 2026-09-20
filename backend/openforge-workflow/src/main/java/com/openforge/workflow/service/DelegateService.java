package com.openforge.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.tenant.TenantContext;
import com.openforge.workflow.entity.WorkflowDelegate;
import com.openforge.workflow.mapper.WorkflowDelegateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批委托（v1.23 设计 §2）：规则CRUD + 生效规则查询。
 * 查询期虚拟收件箱语义——规则停用/过期即刻失效，不动存量任务。
 */
@Service
@RequiredArgsConstructor
public class DelegateService {

    private final WorkflowDelegateMapper delegateMapper;

    @Transactional
    public WorkflowDelegate create(Long principalId, Long agentId, String defKey,
                                   LocalDateTime startTime, LocalDateTime endTime, String remark) {
        if (principalId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未识别当前用户");
        }
        if (agentId == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "被委托人必填");
        }
        if (agentId.equals(principalId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能委托给自己");
        }
        if (startTime == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "开始时间必填");
        }
        if (endTime != null && endTime.isBefore(startTime)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "结束时间不能早于开始时间");
        }
        WorkflowDelegate rule = new WorkflowDelegate();
        rule.setTenantId(TenantContext.getTenantId());
        rule.setPrincipalId(principalId);
        rule.setAgentId(agentId);
        rule.setDefKey(defKey == null || defKey.isBlank() ? null : defKey.trim());
        rule.setStartTime(startTime);
        rule.setEndTime(endTime);
        rule.setEnabled(1);
        rule.setRemark(remark);
        delegateMapper.insert(rule);
        return rule;
    }

    /** 我发出 + 指给我的规则（租户内）。 */
    public List<WorkflowDelegate> mine(Long userId) {
        LambdaQueryWrapper<WorkflowDelegate> wrapper = new LambdaQueryWrapper<WorkflowDelegate>()
                .orderByDesc(WorkflowDelegate::getId);
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && tenantId != 0L) {
            wrapper.eq(WorkflowDelegate::getTenantId, tenantId);
        }
        wrapper.and(w -> w.eq(WorkflowDelegate::getPrincipalId, userId)
                .or().eq(WorkflowDelegate::getAgentId, userId));
        return delegateMapper.selectList(wrapper);
    }

    /** 撤销规则：仅委托人本人可撤销，他人（含被委托人）按不存在应答（R10 语义）。 */
    @Transactional
    public void delete(Long id, Long userId) {
        WorkflowDelegate rule = delegateMapper.selectById(id);
        Long tenantId = TenantContext.getTenantId();
        if (rule == null || userId == null || !userId.equals(rule.getPrincipalId())
                || (tenantId != null && tenantId != 0L && !tenantId.equals(rule.getTenantId()))) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "委托规则不存在");
        }
        delegateMapper.deleteById(id);
    }

    /** userId 当前生效的"被委托"规则（tenant 内，enabled 且时间窗命中）。 */
    public List<WorkflowDelegate> activeForAgent(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<WorkflowDelegate> wrapper = new LambdaQueryWrapper<WorkflowDelegate>()
                .eq(WorkflowDelegate::getAgentId, agentId)
                .eq(WorkflowDelegate::getEnabled, 1)
                .le(WorkflowDelegate::getStartTime, now)
                .and(w -> w.isNull(WorkflowDelegate::getEndTime).or().ge(WorkflowDelegate::getEndTime, now))
                .orderByDesc(WorkflowDelegate::getId);
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && tenantId != 0L) {
            wrapper.eq(WorkflowDelegate::getTenantId, tenantId);
        }
        return delegateMapper.selectList(wrapper);
    }

    /** 任务代办办理权限：principal→agent 是否有覆盖该 defKey 的生效规则。 */
    public boolean hasActiveDelegation(Long principalId, Long agentId, String instanceDefKey) {
        if (principalId == null || agentId == null || principalId.equals(agentId)) {
            return false;
        }
        for (WorkflowDelegate rule : activeForAgent(agentId)) {
            if (!principalId.equals(rule.getPrincipalId())) {
                continue;
            }
            if (rule.getDefKey() == null || rule.getDefKey().equals(instanceDefKey)) {
                return true;
            }
        }
        return false;
    }
}
