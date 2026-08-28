package com.openforge.gateway.trace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 网关链路追踪过滤器（B3）：生成/透传/防伪造/顺序先于认证。 */
class TraceIdGlobalFilterTest {

    private TraceIdGlobalFilter filter;
    private final AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        filter = new TraceIdGlobalFilter();
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(inv -> {
            downstream.set(inv.getArgument(0));
            return Mono.empty();
        });
        this.chain = chain;
    }

    private GatewayFilterChain chain;

    @Test
    @DisplayName("无追踪头：生成 UUID，注入下游并回显响应")
    void generatesTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/parts").build());
        filter.filter(exchange, chain).block();

        String traceId = exchange.getResponse().getHeaders().getFirst(TraceIdGlobalFilter.HEADER_TRACE_ID);
        assertThat(traceId).isNotBlank().matches("[0-9a-f-]{36}");
        assertThat(downstream.get().getRequest().getHeaders()
                .getFirst(TraceIdGlobalFilter.HEADER_TRACE_ID)).isEqualTo(traceId);
    }

    @Test
    @DisplayName("合法外部追踪头：透传（客户端可自备采样 ID）")
    void passesThroughValidTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/parts")
                        .header(TraceIdGlobalFilter.HEADER_TRACE_ID, "abc-123-def").build());
        filter.filter(exchange, chain).block();
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdGlobalFilter.HEADER_TRACE_ID))
                .isEqualTo("abc-123-def");
    }

    @Test
    @DisplayName("非法形态追踪头：剥离并重新生成（防日志注入）")
    void replacesMalformedTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/parts")
                        .header(TraceIdGlobalFilter.HEADER_TRACE_ID, "evil\nINJECTED log").build());
        filter.filter(exchange, chain).block();
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdGlobalFilter.HEADER_TRACE_ID))
                .isNotEqualTo("evil\nINJECTED log")
                .matches("[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("顺序先于认证过滤器（-100），白名单路径同样被追踪")
    void orderBeforeAuthFilter() {
        assertThat(filter.getOrder()).isLessThan(-100);
    }
}
