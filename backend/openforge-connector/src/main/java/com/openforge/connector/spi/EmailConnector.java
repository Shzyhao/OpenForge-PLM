package com.openforge.connector.spi;

import com.openforge.connector.security.EgressGuard;
import com.openforge.connector.spec.ConnectorSpecs;
import com.openforge.connector.spec.SmtpEmailSpec;
import com.openforge.connector.spec.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMTP 邮件连接器（v1.22 连接器扩展包①）：通用 SMTP 出站——发布/变更/审批事件的邮件通知通道。
 * <p>安全：host 经 EgressGuard.checkHost（白名单+私网校验，语义与 HTTP check(url) 对等）；
 * 凭据 {@code SMTP_PASSWORD}（authType SMTP/BASIC 任意，secret 即发件认证密码）——错误消息经脱敏防凭据落日志。
 * <p>实现：JavaMailSender 按 spec 动态装配（host:port:from:username 为缓存键，不注册全局 bean——
 * 每连接器实例自己的 SMTP 配置）。
 */
@Slf4j
@Component
public class EmailConnector implements ConnectorSpi {

    private final EgressGuard egressGuard;
    private final Map<String, JavaMailSenderImpl> senders = new ConcurrentHashMap<>();

    public EmailConnector(EgressGuard egressGuard) {
        this.egressGuard = egressGuard;
    }

    @Override
    public String type() {
        return ConnectorSpecs.TYPE_SMTP_EMAIL;
    }

    @Override
    public ConnectorResult execute(ConnectorExecution execution) {
        SmtpEmailSpec spec = execution.smtpSpec();
        if (spec == null) {
            return ConnectorResult.fail(null, "spec 缺失");
        }
        String secret = execution.credential() == null ? null : execution.credential().secret();
        try {
            // 双保险：Runtime 已做 checkHost，此处保持独立防御（SPI 可被直接调用）
            egressGuard.checkHost(spec.host(), spec.port());
            JavaMailSenderImpl sender = senderOf(spec, secret);
            Map<String, Object> params = execution.params() == null ? Map.of() : execution.params();
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(spec.from());
            message.setTo(spec.to().split(","));
            message.setSubject(String.valueOf(TemplateRenderer.renderTemplate(spec.subject(), params)));
            message.setText(String.valueOf(TemplateRenderer.renderTemplate(spec.bodyText(), params)));
            sender.send(message);
            log.info("SMTP 邮件已发送: host={} from={} to={}", spec.host(), spec.from(), spec.to());
            return ConnectorResult.ok(null, "sent to " + spec.to(), false);
        } catch (com.openforge.common.api.BizException e) {
            throw e; // EGRESS_BLOCKED 语义上抛（BLOCKED 落日志）
        } catch (Exception e) {
            log.warn("SMTP 发送失败: host={} — {}", spec.host(), e.getMessage());
            return ConnectorResult.fail(null, "邮件发送失败: " + sanitize(e.getMessage(), secret));
        }
    }

    /** 按 spec 动态装配 JavaMailSender（缓存键含认证用户名，避免凭据串台）。 */
    private JavaMailSenderImpl senderOf(SmtpEmailSpec spec, String secret) {
        String username = spec.from();
        String key = spec.host() + ":" + spec.port() + ":" + spec.from() + ":" + (secret != null);
        return senders.computeIfAbsent(key, k -> {
            JavaMailSenderImpl impl = new JavaMailSenderImpl();
            impl.setHost(spec.host());
            impl.setPort(spec.port());
            impl.setDefaultEncoding("UTF-8");
            Properties props = impl.getJavaMailProperties();
            props.put("mail.smtp.connectiontimeout", String.valueOf(spec.timeoutMs()));
            props.put("mail.smtp.timeout", String.valueOf(spec.timeoutMs()));
            props.put("mail.smtp.writetimeout", String.valueOf(spec.timeoutMs()));
            if (spec.starttls()) {
                props.put("mail.smtp.starttls.enable", "true");
            }
            if (secret != null && !secret.isBlank()) {
                impl.setUsername(username);
                impl.setPassword(secret);
                props.put("mail.smtp.auth", "true");
            }
            return impl;
        });
    }

    /** 凭据脱敏（HttpRestConnector 同款语义）：secret 出现即替换 ***。 */
    private String sanitize(String text, String secret) {
        if (text == null) {
            return null;
        }
        if (secret != null && !secret.isBlank() && text.contains(secret)) {
            return text.replace(secret, "***");
        }
        return text;
    }
}
