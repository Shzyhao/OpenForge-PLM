package com.openforge.connector.spi;

import com.openforge.connector.security.EgressGuard;
import com.openforge.connector.spec.DingTalkBotSpec;
import com.openforge.connector.spec.FeishuBotSpec;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉钉/飞书机器人集成（v1.22 扩展包②③）：JDK 内置 HttpServer 回环接收，
 * 断言 payload 结构、加签 query 参数（钉钉）、payload 内 sign 字段（飞书）。
 * EgressGuard 用 localhost 白名单（allow-private=true 放行回环）。
 */
class BotConnectorIntegrationTest {

    private static HttpServer server;
    private static int port;
    private static final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> queries = new ConcurrentLinkedQueue<>();

    private final EgressGuard egressGuard = new EgressGuard("localhost,127.0.0.1", true);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/robot/send", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] resp = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        // 飞书独立路径：成功响应为飞书格式（code=0/msg=success）
        server.createContext("/feishu/send", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"code\":0,\"msg\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("钉钉：加签 query 参数 + text payload 结构")
    void dingTalkSignedSend() {
        bodies.clear();
        queries.clear();
        DingTalkBotConnector connector = new DingTalkBotConnector(
                new OutboundClientHolder(egressGuard).client(), egressGuard);
        DingTalkBotSpec spec = new DingTalkBotSpec(1,
                "http://localhost:" + port + "/robot/send?access_token=x", "text",
                null, "物料 {{pn}} 发布", "13800000000", 5000, null, Map.of());
        ConnectorResult result = connector.execute(new ConnectorExecution(
                null, null, null, spec, null,
                Map.of("pn", "P-1"), new ResolvedCredential("WEBHOOK_SECRET", "secret", null)));
        assertThat(result.success()).isTrue();
        assertThat(String.join(";", queries)).contains("timestamp=").contains("&sign=");
        assertThat(String.join(";", bodies)).contains("\"msgtype\":\"text\"")
                .contains("物料 P-1 发布").contains("\"atMobiles\":[\"13800000000\"]");
    }

    @Test
    @DisplayName("飞书：payload 内 sign 字段 + text 结构")
    void feishuSignedSend() {
        bodies.clear();
        FeishuBotConnector connector = new FeishuBotConnector(
                new OutboundClientHolder(egressGuard).client(), egressGuard);
        FeishuBotSpec spec = new FeishuBotSpec(1,
                "http://localhost:" + port + "/feishu/send", "text",
                "变更 {{no}} 完成", 5000, null, Map.of());
        ConnectorResult result = connector.execute(new ConnectorExecution(
                null, null, null, null, spec,
                Map.of("no", "ECR-1"), new ResolvedCredential("WEBHOOK_SECRET", "secret", null)));
        assertThat(result.success()).isTrue();
        String body = String.join(";", bodies);
        assertThat(body).contains("\"msg_type\":\"text\"").contains("变更 ECR-1 完成")
                .contains("\"timestamp\":\"").contains("\"sign\":\"");
    }

    /** 测试桥：直接构造绑定 EgressGuard 的共享客户端（生产由 OutboundHttpConfig 提供）。 */
    private record OutboundClientHolder(EgressGuard guard) {
        CloseableHttpClient client() {
            var cm = org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(new com.openforge.connector.security.EgressPinningDnsResolver(guard))
                    .build();
            return org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                    .setConnectionManager(cm).disableRedirectHandling().build();
        }
    }
}
