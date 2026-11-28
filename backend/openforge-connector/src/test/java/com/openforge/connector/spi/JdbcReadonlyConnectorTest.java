package com.openforge.connector.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.connector.security.EgressGuard;
import com.openforge.connector.spec.JdbcReadonlySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 复核①（2026-11-28）：JDBC 连接器此前完全绕过出站白名单——现在 jdbcUrl 目标走 EgressGuard.checkHost。 */
class JdbcReadonlyConnectorTest {

    private JdbcReadonlySpec spec(String url) {
        return new JdbcReadonlySpec(1, url, "readonly_user", null,
                List.of(), "SELECT 1", Map.of(), 200, 1000);
    }

    @Test
    @DisplayName("jdbcTarget 解析：缺省端口按方言/显式端口/库段截断")
    void jdbcTargetParsing() {
        assertThat(JdbcReadonlyConnector.jdbcTarget("jdbc:postgresql://db.host/mydb"))
                .isEqualTo(new JdbcReadonlyConnector.JdbcTarget("db.host", 5432));
        assertThat(JdbcReadonlyConnector.jdbcTarget("jdbc:mysql://db.host:3307/mydb"))
                .isEqualTo(new JdbcReadonlyConnector.JdbcTarget("db.host", 3307));
        assertThat(JdbcReadonlyConnector.jdbcTarget("jdbc:postgresql://localhost/mydb"))
                .isEqualTo(new JdbcReadonlyConnector.JdbcTarget("localhost", 5432));
    }

    @Test
    @DisplayName("白名单外目标：guard 拒绝直接上抛（不伪装成查询失败、不建连接池）")
    void egressGuardRejectsBeforeConnection() {
        @SuppressWarnings("unchecked")
        EgressGuard guard = mock(EgressGuard.class);
        doThrow(new BizException(com.openforge.common.api.ErrorCode.FORBIDDEN, "目标不在出站白名单"))
                .when(guard).checkHost("db.host", 5432);
        JdbcReadonlyConnector connector = new JdbcReadonlyConnector(new ObjectMapper(), guard);

        ConnectorExecution execution = mock(ConnectorExecution.class);
        when(execution.jdbcSpec()).thenReturn(spec("jdbc:postgresql://db.host/mydb"));
        when(execution.credential()).thenReturn(new ResolvedCredential("BASIC", "pw", null));

        assertThatThrownBy(() -> connector.execute(execution))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在出站白名单");
        verify(guard).checkHost("db.host", 5432);
    }

    @Test
    @DisplayName("白名单内目标：guard 放行后因无真实数据库走连接失败 fail 路径")
    void egressGuardPassThenConnectFailsGracefully() {
        @SuppressWarnings("unchecked")
        EgressGuard guard = mock(EgressGuard.class);
        JdbcReadonlyConnector connector = new JdbcReadonlyConnector(new ObjectMapper(), guard);

        ConnectorExecution execution = mock(ConnectorExecution.class);
        when(execution.jdbcSpec()).thenReturn(spec("jdbc:postgresql://localhost:1/mydb"));
        when(execution.credential()).thenReturn(new ResolvedCredential("BASIC", "pw", null));
        when(execution.params()).thenReturn(Map.of());

        var result = connector.execute(execution);
        assertThat(result.success()).isFalse();   // 连接失败（端口 1 不可达），但 guard 已放行
        verify(guard).checkHost("localhost", 1);
    }
}
