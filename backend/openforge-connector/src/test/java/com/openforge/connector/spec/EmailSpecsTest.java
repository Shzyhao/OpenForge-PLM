package com.openforge.connector.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** SMTP_EMAIL spec 校验矩阵（v1.22 连接器扩展包①）。 */
class EmailSpecsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> validSpec() {
        return Map.of(
                "schemaVersion", 1,
                "host", "smtp.example.com",
                "port", 587,
                "starttls", true,
                "from", "plm@example.com",
                "to", "a@example.com,b@example.com",
                "subject", "【PLM】{{event}}",
                "bodyText", "物料 {{partNumber}} 已发布",
                "parameterSchema", Map.of("properties",
                        Map.of("event", Map.of(), "partNumber", Map.of())),
                "credentialRef", "smtp_cred");
    }

    @Test
    @DisplayName("合法 spec 解析通过且字段收敛")
    void validSpecParses() {
        SmtpEmailSpec spec = ConnectorSpecs.parseSmtpEmail(validSpec(), mapper, true);
        assertThat(spec.host()).isEqualTo("smtp.example.com");
        assertThat(spec.port()).isEqualTo(587);
        assertThat(spec.starttls()).isTrue();
        assertThat(spec.to()).contains(",");
        assertThat(spec.subject()).contains("{{event}}");
    }

    @Test
    @DisplayName("校验矩阵：host 缺失/端口越界/from 与 to 非法/schemaVersion/占位符未声明/凭据引用缺失")
    void validationMatrix() {
        // host 缺失
        Map<String, Object> noHost = new java.util.HashMap<>(validSpec());
        noHost.remove("host");
        assertThatThrownBy(() -> ConnectorSpecs.parseSmtpEmail(noHost, mapper, true))
                .isInstanceOf(BizException.class);
        // 端口越界
        Map<String, Object> badPort = new java.util.HashMap<>(validSpec());
        badPort.put("port", 70000);
        assertThatThrownBy(() -> ConnectorSpecs.parseSmtpEmail(badPort, mapper, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("port");
        // from 非法
        Map<String, Object> badFrom = new java.util.HashMap<>(validSpec());
        badFrom.put("from", "not-an-email");
        assertThatThrownBy(() -> ConnectorSpecs.parseSmtpEmail(badFrom, mapper, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("from");
        // to 单个非法（多收件人逐个校验）
        Map<String, Object> badTo = new java.util.HashMap<>(validSpec());
        badTo.put("to", "a@example.com,bad");
        assertThatThrownBy(() -> ConnectorSpecs.parseSmtpEmail(badTo, mapper, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("to");
        // schemaVersion
        Map<String, Object> badVersion = new java.util.HashMap<>(validSpec());
        badVersion.put("schemaVersion", 2);
        assertThatThrownBy(() -> ConnectorSpecs.parseSmtpEmail(badVersion, mapper, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("schemaVersion");
        // 占位符未声明
        Map<String, Object> undeclared = new java.util.HashMap<>(validSpec());
        undeclared.put("subject", "{{undeclaredParam}}");
        assertThatThrownBy(() -> ConnectorSpecs.parseSmtpEmail(undeclared, mapper, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("parameterSchema");
        // 凭据引用缺失
        assertThatThrownBy(() -> ConnectorSpecs.parseSmtpEmail(validSpec(), mapper, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("credentialRef");
    }

    @Test
    @DisplayName("TYPE_SMTP_EMAIL 进入支持类型集；checkType 通过")
    void typeRegistered() {
        assertThatCode(() -> ConnectorSpecs.checkType(ConnectorSpecs.TYPE_SMTP_EMAIL))
                .doesNotThrowAnyException();
    }
}
