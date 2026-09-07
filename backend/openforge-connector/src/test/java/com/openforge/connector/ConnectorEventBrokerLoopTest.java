package com.openforge.connector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-2 真实 RocketMQ 回路（Testcontainers，CI 真实执行/本机 Docker 不可用自动跳过，
 * 样板沿 KnowledgeEventBrokerLoopTest）：发布 openforge-doc/doc.released →
 * ConnectorEventConsumer 真实消费 → EVENT 触发执行命中 mock 目标（payload 渲染进 URL）。
 */
@SpringBootTest
class ConnectorEventBrokerLoopTest {

    private static final String IMAGE = "apache/rocketmq:5.3.1";
    private static final String[] TOPICS = {
            "openforge-meta", "openforge-object", "openforge-doc",
            "openforge-change", "openforge-task", "openforge-connector"};

    private static Network network;
    private static GenericContainer<?> namesrv;
    private static GenericContainer<?> broker;
    private static HttpServer target;
    private static final ConcurrentLinkedQueue<String> HIT_PATHS = new ConcurrentLinkedQueue<>();

    @Autowired
    private com.openforge.common.event.EventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startBrokerIfDockerAvailable() {
        boolean docker;
        try {
            docker = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "Docker 不可用，跳过真实 MQ 回路测试");

        try {
            target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            target.createContext("/", (HttpExchange exchange) -> {
                HIT_PATHS.add(exchange.getRequestURI().toString());
                exchange.sendResponseHeaders(200, -1);
            });
            target.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        network = Network.newNetwork();
        namesrv = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(network).withNetworkAliases("namesrv")
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m -Xmn128m")
                .withCommand("sh", "mqnamesrv")
                .withExposedPorts(9876)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                        .forLogMessage(".*Name Server boot success.*", 1))
                .withStartupTimeout(Duration.ofSeconds(90));
        namesrv.start();
        broker = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(network).withNetworkAliases("broker")
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m -Xmn128m -XX:MaxDirectMemorySize=256m")
                .withCommand("sh", "-c",
                        "printf 'brokerIP1 = 127.0.0.1\\nautoCreateTopicEnable = true\\nlistenPort = 10911\\n' "
                                + "> /home/rocketmq/b.conf && sh mqbroker -n namesrv:9876 -c /home/rocketmq/b.conf")
                .withCreateContainerCmdModifier(cmd -> cmd.getPortBindings().add(
                        new com.github.dockerjava.api.model.PortBinding(
                                com.github.dockerjava.api.model.Ports.Binding.bindPort(10911),
                                new com.github.dockerjava.api.model.ExposedPort(10911))))
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                        .forLogMessage(".*boot success.*", 1))
                .withStartupTimeout(Duration.ofSeconds(120));
        broker.start();
        try {
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            for (String topic : TOPICS) {
                broker.execInContainer("sh", "mqadmin", "updateTopic",
                        "-n", "namesrv:9876", "-b", "127.0.0.1:10911", "-t", topic);
            }
        } catch (Exception e) {
            throw new IllegalStateException("topic 预建失败: " + e.getMessage(), e);
        }
    }

    @AfterAll
    static void stop() {
        if (target != null) target.stop(0);
        if (broker != null) broker.stop();
        if (namesrv != null) namesrv.stop();
        if (network != null) network.close();
    }

    @DynamicPropertySource
    static void eventProps(DynamicPropertyRegistry registry) {
        if (namesrv != null && namesrv.isRunning()) {
            registry.add("openforge.event.enabled", () -> "true");
            registry.add("openforge.event.namesrv-addr",
                    () -> namesrv.getHost() + ":" + namesrv.getMappedPort(9876));
        }
    }

    @Test
    @DisplayName("真实 MQ 回路：doc.released → 连接器消费者分发 → EVENT 执行命中目标")
    void publishThenTriggerFires() throws Exception {
        Assumptions.assumeTrue(namesrv != null && namesrv.isRunning(), "broker 未启动");

        // 已发布 EVENT 触发连接器（直插主档，PUBLISHED 状态即被消费者/调度器全量扫描命中）
        String code = "mq_evt_" + System.currentTimeMillis();
        String spec = "{\"schemaVersion\":1,\"method\":\"GET\",\"url\":\"http://127.0.0.1:"
                + target.getAddress().getPort() + "/mq?code={{code}}\"}";
        jdbc.update("INSERT INTO conn_definition (conn_code, conn_name, conn_type, status, current_version, "
                        + "spec_json, trigger_type, trigger_json, tenant_id) VALUES (?,?,?,?,?,?,?,?,0)",
                code, "MQ 回路连接器", "HTTP_REST", "PUBLISHED", 1, spec, "EVENT",
                "{\"topic\":\"openforge-doc\",\"tag\":\"doc.released\"}");

        long marker = System.currentTimeMillis();
        boolean sent = eventPublisher.publish("openforge-doc", "doc.released",
                Map.of("code", "MQ-" + marker));
        assertThat(sent).as("事件应真实进入 broker").isTrue();

        // 新消费组 LAST_OFFSET + 再均衡，轮询等待触发执行（目标命中 + 执行日志双证据）
        long deadline = System.currentTimeMillis() + 60_000;
        boolean fired;
        do {
            Thread.sleep(2000);
            fired = HIT_PATHS.stream().anyMatch(p -> p.contains("MQ-" + marker));
        } while (!fired && System.currentTimeMillis() < deadline);

        assertThat(HIT_PATHS.stream().filter(p -> p.contains("MQ-" + marker)).findFirst())
                .as("EVENT 触发应携带事件 payload 命中目标").isPresent();
        Integer execLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conn_exec_log e JOIN conn_definition d ON e.conn_id = d.id "
                        + "WHERE d.conn_code = '" + code + "' AND e.trigger_type='EVENT'", Integer.class);
        assertThat(execLog).isGreaterThan(0);
    }
}
