package com.openforge.connector.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JDBC 只读 spec 校验单测：URL 方言/写关键字/白名单闭包/命名参数/边界。 */
class JdbcSpecsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> specMap(String json) throws Exception {
        return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
    }

    private String validSpec(String sql, String tables) {
        return """
                {"schemaVersion":1,
                 "datasource":{"jdbcUrl":"jdbc:postgresql://legacy:5432/mes","username":"readonly_user"},
                 "passwordRef":"cred_pg",
                 "allowedTables":[%s],
                 "parameterSchema":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]},
                 "sqlTemplate":"%s",
                 "maxRows":50,"timeoutMs":3000}
                """.formatted(tables, sql);
    }

    @Test
    @DisplayName("合法 spec 解析出全字段")
    void parseValid() throws Exception {
        JdbcReadonlySpec spec = ConnectorSpecs.parseJdbcReadonly(
                specMap(validSpec("SELECT item_code FROM mes_stock WHERE item_code = :code", "\"mes_stock\"")),
                mapper, true);
        assertThat(spec.username()).isEqualTo("readonly_user");
        assertThat(spec.maxRows()).isEqualTo(50);
        assertThat(spec.allowedTables()).containsExactly("mes_stock");
    }

    @Test
    @DisplayName("不支持的 JDBC 方言拒绝（含 h2）")
    void rejectUnsupportedDialect() {
        assertThatThrownBy(() -> ConnectorSpecs.parseJdbcReadonly(specMap("""
                {"schemaVersion":1,"datasource":{"jdbcUrl":"jdbc:h2:mem:x","username":"sa"},
                 "allowedTables":["t"],"sqlTemplate":"SELECT 1"}
                """), mapper, true)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("写关键字 / 多语句 / 非 SELECT 拒绝")
    void rejectWriteSql() {
        assertThatThrownBy(() -> ConnectorSpecs.checkReadonlySql(
                "DELETE FROM mes_stock", java.util.List.of("mes_stock"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.checkReadonlySql(
                "SELECT 1; DROP TABLE mes_stock", java.util.List.of("mes_stock"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.checkReadonlySql(
                "UPDATE mes_stock SET qty = 1", java.util.List.of("mes_stock"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.checkReadonlySql(
                "INSERT INTO mes_stock VALUES (1)", java.util.List.of("mes_stock"))).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("引用表不在白名单拒绝（含 schema 前缀取末段校验）")
    void rejectTableOutsideWhitelist() {
        assertThatThrownBy(() -> ConnectorSpecs.checkReadonlySql(
                "SELECT * FROM other_table", java.util.List.of("mes_stock"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.checkReadonlySql(
                "SELECT * FROM public.mes_stock b JOIN secret d ON 1=1", java.util.List.of("mes_stock")))
                .isInstanceOf(BizException.class);
        // schema 前缀命中末段 → 通过
        ConnectorSpecs.checkReadonlySql("SELECT * FROM public.mes_stock",
                java.util.List.of("mes_stock"));
    }

    @Test
    @DisplayName("命名参数未声明 / parameterSchema 缺失拒绝")
    void rejectUndeclaredNamedParam() throws Exception {
        assertThatThrownBy(() -> ConnectorSpecs.parseJdbcReadonly(specMap("""
                {"schemaVersion":1,"datasource":{"jdbcUrl":"jdbc:postgresql://l:5432/m","username":"u"},
                 "allowedTables":["mes_stock"],
                 "sqlTemplate":"SELECT 1 FROM mes_stock WHERE x = :undeclared"}
                """), mapper, true)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.parseJdbcReadonly(specMap("""
                {"schemaVersion":1,"datasource":{"jdbcUrl":"jdbc:postgresql://l:5432/m","username":"u"},
                 "allowedTables":["mes_stock"],"sqlTemplate":"SELECT 1 FROM mes_stock WHERE x = :p",
                 "parameterSchema":{"type":"object","properties":{"other":{"type":"string"}}}}
                """), mapper, true)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("passwordRef 不存在拒绝；maxRows/timeoutMs 越界拒绝")
    void rejectBounds() {
        assertThatThrownBy(() -> ConnectorSpecs.parseJdbcReadonly(
                specMap(validSpec("SELECT 1 FROM mes_stock", "\"mes_stock\"")), mapper, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cred_pg");
        assertThatThrownBy(() -> ConnectorSpecs.parseJdbcReadonly(specMap("""
                {"schemaVersion":1,"datasource":{"jdbcUrl":"jdbc:postgresql://l:5432/m","username":"u"},
                 "allowedTables":["mes_stock"],"sqlTemplate":"SELECT 1 FROM mes_stock","maxRows":9999}
                """), mapper, true)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ConnectorSpecs.parseJdbcReadonly(specMap("""
                {"schemaVersion":1,"datasource":{"jdbcUrl":"jdbc:postgresql://l:5432/m","username":"u"},
                 "allowedTables":["mes_stock"],"sqlTemplate":"SELECT 1 FROM mes_stock","timeoutMs":10}
                """), mapper, true)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("required 必填参数检查（共享逻辑）")
    void checkRequiredParams() {
        Map<String, Object> schema = Map.of("required", java.util.List.of("code"));
        assertThatThrownBy(() -> ConnectorSpecs.checkRequiredParams(schema, Map.of()))
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(1000);
        ConnectorSpecs.checkRequiredParams(schema, Map.of("code", "x"));
    }
}
