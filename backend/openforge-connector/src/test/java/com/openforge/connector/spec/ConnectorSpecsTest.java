package com.openforge.connector.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec 解析校验单测：合法解析、非法拒绝（错误码 6006）、占位符闭包。 */
class ConnectorSpecsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> specMap(String json) throws Exception {
        return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
    }

    @Test
    @DisplayName("合法 spec 解析出全字段与默认值")
    void parseValid() throws Exception {
        HttpRestSpec spec = ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"post","url":"https://erp.example.com/api",
                 "headers":{"Accept":"application/json"},"timeoutMs":8000,
                 "parameterSchema":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]},
                 "requestTemplate":{"body":{"code":"{{code}}"}},"retry":{"maxAttempts":3,"backoffMs":200}}
                """), mapper, true);
        assertThat(spec.method()).isEqualTo("POST");
        assertThat(spec.timeoutMs()).isEqualTo(8000);
        assertThat(spec.retry().maxAttempts()).isEqualTo(3);
        assertThat(spec.retry().backoffMs()).isEqualTo(200);
        assertThat(spec.credentialRef()).isNull();
    }

    @Test
    @DisplayName("schemaVersion 非 1 拒绝")
    void rejectWrongSchemaVersion() {
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(
                Map.of("schemaVersion", 2, "method", "GET", "url", "https://a.com"), mapper, true))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(6006);
    }

    @Test
    @DisplayName("非法 method / url 拒绝")
    void rejectBadMethodAndUrl() {
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(
                Map.of("schemaVersion", 1, "method", "DELETE", "url", "https://a.com"), mapper, true))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(
                Map.of("schemaVersion", 1, "method", "GET", "url", "ftp://a.com"), mapper, true))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(
                Map.of("schemaVersion", 1, "method", "GET", "url", "https://"), mapper, true))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("Authorization 等敏感头禁止显式携带")
    void rejectForbiddenHeader() {
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"GET","url":"https://a.com",
                 "headers":{"Authorization":"Bearer hardcoded"}}
                """), mapper, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Authorization");
    }

    @Test
    @DisplayName("占位符未声明 parameterSchema / 未在 properties 中声明 → 拒绝")
    void rejectUndeclaredPlaceholder() {
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"POST","url":"https://a.com/{{id}}"}
                """), mapper, true))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"POST","url":"https://a.com/{{id}}",
                 "parameterSchema":{"type":"object","properties":{"name":{"type":"string"}}}}
                """), mapper, true))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("credentialRef 引用不存在 → 拒绝")
    void rejectMissingCredential() {
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"GET","url":"https://a.com","credentialRef":"cred_x"}
                """), mapper, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cred_x");
    }

    @Test
    @DisplayName("超时与重试参数越界拒绝")
    void rejectOutOfRange() {
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"GET","url":"https://a.com","timeoutMs":60000}
                """), mapper, true))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"GET","url":"https://a.com","retry":{"maxAttempts":9}}
                """), mapper, true))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("GET 带 requestTemplate 拒绝")
    void rejectGetWithBody() {
        assertThatThrownBy(() -> ConnectorSpecs.parseHttpRest(specMap("""
                {"schemaVersion":1,"method":"GET","url":"https://a.com",
                 "requestTemplate":{"body":{"a":1}}}
                """), mapper, true))
                .isInstanceOf(BizException.class);
    }
}
