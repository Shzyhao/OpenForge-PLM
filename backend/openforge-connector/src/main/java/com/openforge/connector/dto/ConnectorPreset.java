package com.openforge.connector.dto;

import java.util.List;
import java.util.Map;

/**
 * 通知渠道连接器模板（v1.26 设计 §3）：每类型一份最小合法 spec 骨架（含 notify.created
 * 事件变量占位——{{title}}/{{content}}/{{email}} 由 TemplateRenderer 按触发 payload 渲染），
 * 前端"从模板新建"预填，用户只需补连接信息（host/webhook/凭据）与触发配置。
 */
public record ConnectorPreset(String connType, String name, String description,
                              Map<String, Object> spec,
                              List<Map<String, Object>> variables) {

    public static List<ConnectorPreset> notifyPresets() {
        return List.of(
                new ConnectorPreset("SMTP_EMAIL", "邮件通知", "按 notify.created 收件人 email 发送站内事件邮件",
                        Map.of("schemaVersion", 1,
                                "host", "smtp.example.com",
                                "port", 465,
                                "from", "plm@example.com",
                                "to", "{{email}}",
                                "subject", "[OpenForge] {{title}}",
                                "bodyText", "{{content}}\n\n—— OpenForge PLM 站内通知转发"),
                        List.of(var("email", "收件人邮箱（notify.created 携带）"),
                                var("title", "通知标题"), var("content", "通知内容"))),
                new ConnectorPreset("DINGTALK_BOT", "钉钉群通知", "站内事件转发钉钉群机器人（加签）",
                        Map.of("schemaVersion", 1,
                                "webhookUrl", "https://oapi.dingtalk.com/robot/send?access_token=REPLACE_ME",
                                "msgtype", "text",
                                "title", "OpenForge 通知",
                                "textTemplate", "[{{title}}]\n{{content}}"),
                        List.of(var("title", "通知标题"), var("content", "通知内容"))),
                new ConnectorPreset("FEISHU_BOT", "飞书群通知", "站内事件转发飞书群机器人（payload 内加签）",
                        Map.of("schemaVersion", 1,
                                "webhookUrl", "https://open.feishu.cn/open-apis/bot/v2/hook/REPLACE_ME",
                                "msgType", "text",
                                "textTemplate", "[{{title}}]\n{{content}}"),
                        List.of(var("title", "通知标题"), var("content", "通知内容"))),
                new ConnectorPreset("HTTP_REST", "通用 Webhook", "站内事件 POST 到任意外部端点（requestTemplate 渲染）",
                        Map.of("schemaVersion", 1,
                                "method", "POST",
                                "url", "https://example.com/hook/openforge",
                                "requestTemplate", Map.of("title", "{{title}}", "content", "{{content}}")),
                        List.of(var("title", "通知标题"), var("content", "通知内容"))));
    }

    private static Map<String, Object> var(String name, String desc) {
        return Map.of("name", name, "description", desc);
    }
}
