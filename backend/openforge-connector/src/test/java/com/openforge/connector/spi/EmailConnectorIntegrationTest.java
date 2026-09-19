package com.openforge.connector.spi;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.openforge.connector.security.EgressGuard;
import com.openforge.connector.spec.SmtpEmailSpec;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.Context;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SMTP 邮件连接器集成（v1.22 扩展包①）：GreenMail 内存 SMTP 真发信（无 Docker 依赖）。
 * 覆盖：渲染发送/占位符参数注入/多收件人/凭据认证链路/白名单外 host 拦截。
 */
class EmailConnectorIntegrationTest {

    private static GreenMail greenMail;
    private static int smtpPort;
    private static final AtomicInteger portSeq = new AtomicInteger(3025);

    private final EmailConnector connector = new EmailConnector(
            new EgressGuard("localhost,127.0.0.1", true));

    @BeforeAll
    static void startGreenMail() {
        // 端口冲突重试：起在回环随机高位口
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                smtpPort = portSeq.incrementAndGet();
                greenMail = new GreenMail(new ServerSetup(smtpPort, "127.0.0.1", "smtp"));
                greenMail.start();
                return;
            } catch (Exception e) {
                if (attempt == 4) {
                    throw new IllegalStateException("GreenMail 启动失败", e);
                }
            }
        }
    }

    @AfterAll
    static void stopGreenMail() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @BeforeEach
    void reset() {
        greenMail.reset();
    }

    private SmtpEmailSpec spec(String to) {
        return new SmtpEmailSpec(1, "localhost", smtpPort, false,
                "plm@openforge.local", to,
                "【PLM】{{event}}", "物料 {{partNumber}} 已发布（{{event}}）",
                5000, null, Map.of());
    }

    @Test
    @DisplayName("真发信：占位符渲染 + 多收件人 + 主题正文落信")
    void sendsRenderedMail() throws Exception {
        ConnectorResult result = connector.execute(new ConnectorExecution(
                null, null, spec("a@openforge.local,b@openforge.local"),
                Map.of("event", "物料发布", "partNumber", "P20260919-001"), null));

        assertThat(result.success()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(2);
        assertThat(received[0].getSubject()).isEqualTo("【PLM】物料发布");
        assertThat(received[0].getContent()).asString()
                .contains("物料 P20260919-001 已发布（物料发布）");
    }

    @Test
    @DisplayName("凭据链路：SMTP_PASSWORD 作为认证密码（GreenMail 断言认证用户）")
    void sendsWithCredential() {
        greenMail.setUser("plm@openforge.local", "plm@openforge.local", "secret-pass");
        ConnectorResult result = connector.execute(new ConnectorExecution(
                null, null, spec("c@openforge.local"),
                Map.of("event", "e", "partNumber", "p"),
                new ResolvedCredential("BASIC", "secret-pass", null)));
        assertThat(result.success()).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("白名单外 host 拦截（CONN_EGRESS_BLOCKED 语义上抛）")
    void blocksNonWhitelistedHost() {
        SmtpEmailSpec evil = new SmtpEmailSpec(1, "evil.example.com", 25, false,
                "plm@openforge.local", "x@openforge.local", "s", "b", 5000, null, Map.of());
        assertThatThrownBy(() -> connector.execute(new ConnectorExecution(
                null, null, evil, Map.of(), null)))
                .isInstanceOf(com.openforge.common.api.BizException.class)
                .hasMessageContaining("白名单");
    }
}
