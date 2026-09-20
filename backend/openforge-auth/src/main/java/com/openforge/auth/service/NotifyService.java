package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.auth.dto.PageResponse;
import com.openforge.auth.entity.NotifyMessage;
import com.openforge.auth.mapper.NotifyMessageMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 站内通知（v1.23 设计 §1）：双通道统一摄取入口 + 收件箱读写。
 * 收件人解析与租户归属一律以 sys_user 记录为准——MQ 信封与内部 HTTP 两通道
 * 都不需要（也不应该）信任调用方申报的租户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    /** ROLE 任务 fan-out 上限：角色成员过多时截断（通知是 UX 数据，宁缺勿炸） */
    static final int ROLE_FANOUT_CAP = 50;

    private final NotifyMessageMapper mapper;
    private final JdbcTemplate jdbc;

    /** 事件→通知落库（幂等由通道层保证：MQ 走 sys_event_consumed，HTTP 天然单次）。 */
    public void ingest(String eventType, Map<String, Object> payload) {
        if (eventType == null || payload == null) {
            return;
        }
        switch (eventType) {
            case "task.created" -> ingestTaskCreated(eventType, payload);
            case "task.completed" -> ingestTaskCompleted(payload);
            default -> log.debug("通知中心未订阅的事件类型，忽略: {}", eventType);
        }
    }

    private void ingestTaskCreated(String eventType, Map<String, Object> payload) {
        String nodeName = str(payload.get("nodeName"));
        String bizType = str(payload.get("bizType"));
        Long bizId = num(payload.get("bizId"));
        String content = "流程实例 #" + num(payload.get("instanceId"))
                + (bizType == null || bizType.isBlank() ? "" : "（" + bizType + (bizId == null ? "" : "#" + bizId) + "）")
                + " 等待审批";
        List<Long> recipients = new ArrayList<>();
        Long assigneeId = num(payload.get("assigneeId"));
        if (assigneeId != null && assigneeId > 0) {
            recipients.add(assigneeId);
        } else {
            String role = str(payload.get("candidateRole"));
            if (role != null && !role.isBlank()) {
                recipients.addAll(roleMembers(role));
            }
        }
        for (Long userId : recipients) {
            insert(userId, eventType, bizType, bizId, "待办审批：" + (nodeName.isBlank() ? "审批任务" : nodeName), content);
        }
    }

    private void ingestTaskCompleted(Map<String, Object> payload) {
        Long initiatorId = num(payload.get("initiatorId"));
        if (initiatorId == null || initiatorId <= 0) {
            return; // 发起人未知（如 internal 启动未带发起人）：无收件人语义，放弃
        }
        String action = str(payload.get("action"));
        String result = switch (action) {
            case "APPROVE" -> "已通过";
            case "REJECT" -> "已驳回";
            case "CANCELLED" -> "已取消";
            default -> "有新进展";
        };
        String nodeName = str(payload.get("nodeName"));
        String bizType = str(payload.get("bizType"));
        insert(initiatorId, "task.completed", bizType, num(payload.get("bizId")),
                "审批进展：" + (nodeName.isBlank() ? "审批任务" : nodeName) + " " + result,
                "流程实例 #" + num(payload.get("instanceId")) + " 的审批节点"
                        + (nodeName.isBlank() ? "" : "「" + nodeName + "」") + result);
    }

    private void insert(Long userId, String eventType, String bizType, Long bizId, String title, String content) {
        Long tenantId = userTenant(userId);
        if (tenantId == null) {
            return; // 收件人不存在或已删：跳过（通知为尽力投递）
        }
        NotifyMessage msg = new NotifyMessage();
        msg.setTenantId(tenantId);
        msg.setUserId(userId);
        msg.setEventType(eventType);
        msg.setBizType(bizType);
        msg.setBizId(bizId);
        msg.setTitle(title);
        msg.setContent(content);
        msg.setReadFlag(0);
        mapper.insert(msg);
    }

    /** 角色成员（同租户、未删、ACTIVE），fan-out 截断。 */
    List<Long> roleMembers(String roleCode) {
        Long tenantId = TenantContext.getTenantId();
        String tenantClause = (tenantId == null || tenantId == 0L) ? "" : "AND u.tenant_id = " + tenantId + " ";
        List<Long> ids = jdbc.query(
                "SELECT u.id FROM sys_user u "
                        + "JOIN sys_user_role ur ON ur.user_id = u.id "
                        + "JOIN sys_role r ON r.id = ur.role_id "
                        + "WHERE r.role_code = ? AND u.deleted = 0 AND u.status = 'ACTIVE' " + tenantClause
                        + "ORDER BY u.id LIMIT " + ROLE_FANOUT_CAP,
                (rs, i) -> rs.getLong(1), roleCode);
        log.debug("通知角色 fan-out: role={}, 租户={}, 命中={}", roleCode, tenantId, ids.size());
        return ids;
    }

    private Long userTenant(Long userId) {
        List<Long> tenants = jdbc.query(
                "SELECT tenant_id FROM sys_user WHERE id = ? AND deleted = 0",
                (rs, i) -> rs.getLong(1), userId);
        return tenants.isEmpty() ? null : tenants.get(0);
    }

    // ===== 收件箱 =====

    public PageResponse<NotifyMessage> inbox(Long userId, boolean unreadOnly, long page, long size) {
        LambdaQueryWrapper<NotifyMessage> wrapper = new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .orderByDesc(NotifyMessage::getId);
        if (unreadOnly) {
            wrapper.eq(NotifyMessage::getReadFlag, 0);
        }
        Page<NotifyMessage> result = mapper.selectPage(Page.of(page, Math.min(Math.max(size, 1), 100)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public long unreadCount(Long userId) {
        return mapper.selectCount(new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .eq(NotifyMessage::getReadFlag, 0));
    }

    /** 标记已读：仅本人；他人（跨用户/跨租户）按不存在应答（R10 语义）。 */
    public void markRead(Long id, Long userId) {
        NotifyMessage msg = mapper.selectById(id);
        if (msg == null || userId == null || !userId.equals(msg.getUserId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "通知不存在");
        }
        if (msg.getReadFlag() == 0) {
            msg.setReadFlag(1);
            mapper.updateById(msg);
        }
    }

    public void markAllRead(Long userId) {
        NotifyMessage patch = new NotifyMessage();
        patch.setReadFlag(1);
        mapper.update(patch, new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .eq(NotifyMessage::getReadFlag, 0));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long num(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
