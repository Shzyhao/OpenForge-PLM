package com.openforge.knowledge.event;

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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2-2 真实 RocketMQ 回路（Testcontainers，CI 真实执行/本机 Docker 不可用自动跳过）：
 * namesrv+broker 真实起 → EVENT_ENABLED=true → 经 EventPublisher 发 schema.migrated →
 * 同应用内消费者真实消费 → knowledge_item 出现 SCHEMA 条目（幂等表/租户/traceId 全链路）。
 */
@SpringBootTest
class KnowledgeEventBrokerLoopTest {

    private static final String IMAGE = "apache/rocketmq:5.3.1";
    private static Network network;
    private static GenericContainer<?> namesrv;
    private static GenericContainer<?> broker;

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
        network = Network.newNetwork();
        namesrv = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(network).withNetworkAliases("namesrv")
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m -Xmn128m")   // 官方脚本默认 4g，CI 7GB 机器起不来
                .withCommand("sh", "mqnamesrv")
                .withExposedPorts(9876)
                .waitingFor(Wait.forLogMessage(".*Name Server boot success.*", 1))
                .withStartupTimeout(Duration.ofSeconds(90));
        namesrv.start();
        // brokerIP1=127.0.0.1：broker 向 namesrv 注册宿主回环地址，host 侧客户端经固定映射端口回连
        broker = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(network).withNetworkAliases("broker")
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m -Xmn128m -XX:MaxDirectMemorySize=256m")   // 官方脚本默认 8g
                .withCommand("sh", "-c",
                        "printf 'brokerIP1 = 127.0.0.1\\nautoCreateTopicEnable = true\\nlistenPort = 10911\\n' "
                                + "> /home/rocketmq/b.conf && sh mqbroker -n namesrv:9876 -c /home/rocketmq/b.conf")
                .withCreateContainerCmdModifier(cmd -> cmd.getPortBindings().add(
                        new com.github.dockerjava.api.model.PortBinding(
                                com.github.dockerjava.api.model.Ports.Binding.bindPort(10911),
                                new com.github.dockerjava.api.model.ExposedPort(10911))))
                .waitingFor(Wait.forLogMessage(".*boot success.*", 1))
                .withStartupTimeout(Duration.ofSeconds(120));
        broker.start();
    }

    @AfterAll
    static void stopBroker() {
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
    @DisplayName("真实 MQ 回路：EventPublisher 发布 schema.migrated → 消费者落 knowledge_item")
    void publishThenConsumerSinks() throws Exception {
        Assumptions.assumeTrue(namesrv != null && namesrv.isRunning(), "broker 未启动");
        long marker = System.currentTimeMillis();
        boolean sent = eventPublisher.publish("openforge-meta", "schema.migrated", Map.of(
                "objectKey", "mq_loop_" + marker,
                "displayName", "MQ 回路对象",
                "tableName", "dyn_mq_loop",
                "version", 1,
                "description", "名称 name(STRING,必填)"));
        assertThat(sent).as("事件应真实进入 broker").isTrue();

        // 消费者已在 ApplicationReady 连接（新消费组 FIRST_OFFSET + 20s 再均衡，轮询等待落库）
        long deadline = System.currentTimeMillis() + 60_000;
        Long cnt;
        do {
            Thread.sleep(2000);
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_item WHERE source_type='SCHEMA' AND source_ref=?",
                    Long.class, "mq_loop_" + marker);
            cnt = c == null ? 0 : c;
        } while (cnt == 0 && System.currentTimeMillis() < deadline);
        assertThat(cnt).as("60s 内应被消费落库").isEqualTo(1);
    }
}
