package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.auth.dto.PageResponse;
import com.openforge.auth.entity.NotifyMessage;
import com.openforge.auth.entity.NotifySubscription;
import com.openforge.auth.mapper.NotifyMessageMapper;
import com.openforge.auth.mapper.NotifySubscriptionMapper;
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

    /** 可订阅的发布类事件（v1.24 设计 §4）：payload 无收件人语义，改由用户显式订阅 */
    public static final java.util.Set<String> SUBSCRIBABLE_EVENTS = java.util.Set.of(
            "part.released", "bom.published", "doc.released", "drawing.released", "drawing.obsolete");

    private final NotifyMessageMapper mapper;
    private final NotifySubscriptionMapper subscriptionMapper;
    private final JdbcTemplate jdbc;
    private final com.openforge.common.event.EventPublisher eventPublisher;

    /** 事件→通知落库（幂等由通道层保证：MQ 走 sys_event_consumed，HTTP 天然单次）。 */
    public void ingest(String eventType, Map<String, Object> payload) {
        if (eventType == null || payload == null) {
            return;
        }
        switch (eventType) {
            case "task.created" -> ingestTaskCreated(eventType, payload);
            case "task.completed" -> ingestTaskCompleted(payload);
            // 发布类事件（v1.24 §4）：收件人 = 同租户订阅者，fan-out 同 ROLE 通道截断
            case "part.released" -> subscriberNotify(eventType, payload,
                    "物料发布：" + str(payload.get("partNumber")) + " " + str(payload.get("partName")),
                    "物料 " + str(payload.get("partNumber")) + " 版本 " + str(payload.get("version")) + " 已发布生效");
            case "bom.published" -> subscriberNotify(eventType, payload,
                    "BOM 发布：" + str(payload.get("bomNumber")),
                    "BOM " + str(payload.get("bomNumber")) + " 版本 " + str(payload.get("version")) + " 已发布生效");
            case "doc.released" -> subscriberNotify(eventType, payload,
                    "文档发布：" + str(payload.get("title")),
                    "文档「" + str(payload.get("title")) + "」新版本已发布生效");
            case "drawing.released" -> subscriberNotify(eventType, payload,
                    "图纸发布：" + str(payload.get("drawingNumber")),
                    "图纸 " + str(payload.get("drawingNumber")) + " 版本 " + str(payload.get("version")) + " 已发布");
            case "drawing.obsolete" -> subscriberNotify(eventType, payload,
                    "图纸作废：" + str(payload.get("drawingNumber")),
                    "图纸 " + str(payload.get("drawingNumber")) + " 已作废，请停止使用");
            default -> log.debug("通知中心未订阅的事件类型，忽略: {}", eventType);
        }
    }

    /** 发布类事件 → 同租户订阅者 fan-out（上限沿用 ROLE 通道截断）。 */
    private void subscriberNotify(String eventType, Map<String, Object> payload, String title, String content) {
        List<Long> users = subscribers(eventType);
        String bizType = eventType.split("\\.")[0].toUpperCase();
        Long bizId = num(payload.entrySet().stream()
                .filter(e -> e.getKey().endsWith("Id"))
                .map(Map.Entry::getValue).findFirst().orElse(null));
        for (Long userId : users) {
            insert(userId, eventType, bizType, bizId, title, content);
        }
    }

    /** 同租户、且用户仍有效的订阅者（原生 SQL：后台消费线程租户上下文不可靠，显式带租户条件）。 */
    List<Long> subscribers(String eventKey) {
        Long tenantId = TenantContext.getTenantId();
        List<Long> ids = jdbc.query(
                "SELECT s.user_id FROM notify_subscription s "
                        + "JOIN sys_user u ON u.id = s.user_id AND u.deleted = 0 AND u.status = 'ACTIVE' "
                        + "WHERE s.event_key = ? AND s.tenant_id = ? "
                        + "ORDER BY s.user_id LIMIT " + ROLE_FANOUT_CAP,
                (rs, i) -> rs.getLong(1), eventKey, tenantId == null ? 0L : tenantId);
        log.debug("通知订阅 fan-out: event={}, 租户={}, 命中={}", eventKey, tenantId, ids.size());
        return ids;
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
        // 站外渠道分发口（v1.25 §3）：通知类连接器（SMTP/DINGTALK/FEISHU/WEBHOOK）订阅
        // openforge-notify 主题的 notify.created 即可复用全套触发框架；总线关闭=false→仅站内信
        String email = userEmail(userId);
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("notifyId", msg.getId());
        out.put("userId", userId);
        if (email != null) {
            out.put("email", email);
        }
        out.put("eventType", eventType);
        if (bizType != null) {
            out.put("bizType", bizType);
        }
        if (bizId != null) {
            out.put("bizId", bizId);
        }
        out.put("title", title);
        out.put("content", content == null ? "" : content);
        eventPublisher.publish("openforge-notify", "notify.created", out);
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

    /** 收件人 email（站外渠道分发用；未配置为 null）。 */
    private String userEmail(Long userId) {
        List<String> emails = jdbc.query(
                "SELECT email FROM sys_user WHERE id = ? AND deleted = 0 AND email IS NOT NULL",
                (rs, i) -> rs.getString(1), userId);
        return emails.isEmpty() ? null : emails.get(0);
    }

    // ===== 订阅管理（v1.24 §4）=====

    public List<NotifySubscription> subscriptions(Long userId) {
        return subscriptionMapper.selectList(new LambdaQueryWrapper<NotifySubscription>()
                .eq(NotifySubscription::getUserId, userId)
                .orderByAsc(NotifySubscription::getEventKey));
    }

    public void subscribe(Long userId, String eventKey) {
        if (eventKey == null || !SUBSCRIBABLE_EVENTS.contains(eventKey)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不支持订阅的事件类型: " + eventKey);
        }
        Long tenantId = TenantContext.getTenantId();
        if (subscriptionMapper.selectCount(new LambdaQueryWrapper<NotifySubscription>()
                .eq(NotifySubscription::getUserId, userId)
                .eq(NotifySubscription::getEventKey, eventKey)) > 0) {
            return; // 幂等
        }
        NotifySubscription sub = new NotifySubscription();
        sub.setTenantId(tenantId);
        sub.setUserId(userId);
        sub.setEventKey(eventKey);
        subscriptionMapper.insert(sub);
    }

    public void unsubscribe(Long userId, String eventKey) {
        subscriptionMapper.delete(new LambdaQueryWrapper<NotifySubscription>()
                .eq(NotifySubscription::getUserId, userId)
                .eq(NotifySubscription::getEventKey, eventKey));
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
