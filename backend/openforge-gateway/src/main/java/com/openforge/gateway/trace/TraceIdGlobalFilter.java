package com.openforge.gateway.trace;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 链路追踪全局过滤器（B3 可观测）：为每个请求生成/透传 X-Trace-Id——
 * 剥离外部同名头防伪造（与 X-User-Id 同信任模型），注入下游并在响应回显。
 * 顺序先于 AuthGlobalFilter（-100）：白名单路径同样获得追踪。
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        String traceId = (incoming != null && incoming.matches("[0-9a-fA-F-]{1,64}"))
                ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(HEADER_TRACE_ID))
                .header(HEADER_TRACE_ID, traceId)
                .build();
        exchange.getResponse().getHeaders().set(HEADER_TRACE_ID, traceId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -200;   // 先于 AuthGlobalFilter(-100)
    }
}
