package com.openforge.auth.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 配置中心真实验证（Testcontainers，CI 真实执行/本机 Docker 不可用自动跳过）：
 * 真实 Nacos（standalone）→ HTTP 发布 openforge-auth.yml → 应用启动经
 * optional:nacos import 加载 → 远程属性存在且覆盖本地 application.yml 同名项。
 * 默认关闭语义（NACOS_CONFIG_ENABLED=false 时 resolver 跳过）由既有 61 例全绿隐式验证。
 */
@SpringBootTest
class NacosConfigCenterLoopTest {

    private static final String IMAGE = "nacos/nacos-server:v2.2.3";
    private static GenericContainer<?> nacos;

    @Autowired
    private Environment env;

    @BeforeAll
    static void startNacosIfDockerAvailable() throws Exception {
        boolean docker;
        try {
            docker = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "Docker 不可用，跳过 Nacos 配置中心回路测试");
        // host 网络模式（CI 为 Linux）：nacos 直接监听宿主机 8848/9848——
        // Nacos 2.x 客户端从 server-addr 端口推算 gRPC 端口（+1000），host 模式下推算天然成立；
        // 端口映射方案与 Testcontainers 等待策略/端口解析不兼容（已实测三轮）
        nacos = new GenericContainer<>(IMAGE)
                .withEnv("MODE", "standalone")
                .withEnv("NACOS_AUTH_ENABLE", "false")
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m")
                .withNetworkMode("host")
                .waitingFor(Wait.forLogMessage(".*Nacos started successfully.*", 1))
                .withStartupTimeout(Duration.ofSeconds(120));
        nacos.start();
        waitReadiness();

        // 启动前发布远程配置（openforge-auth.yml）——用与应用同栈的 Nacos 客户端，
        // 避免 v1 HTTP API 行为差异；发布后回读确认服务端确已持久化
        java.util.Properties props = new java.util.Properties();
        props.setProperty("serverAddr", "localhost:8848");
        com.alibaba.nacos.api.config.ConfigService configService =
                com.alibaba.nacos.api.NacosFactory.createConfigService(props);
        String content = "openforge:\n  config:\n    probe: from-nacos\n"
                + "server:\n  tomcat:\n    threads:\n      max: 7\n";
        assertThat(configService.publishConfig("openforge-auth.yml", "DEFAULT_GROUP", content)).isTrue();
        String fetched = configService.getConfig("openforge-auth.yml", "DEFAULT_GROUP", 5000);

        // 诊断证据（CI 只读）：HTTP GET 交叉验证 + 服务端日志转储
        HttpRequest httpGet = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8848/nacos/v1/cs/configs?dataId="
                        + URLEncoder.encode("openforge-auth.yml", StandardCharsets.UTF_8)
                        + "&group=" + URLEncoder.encode("DEFAULT_GROUP", StandardCharsets.UTF_8)))
                .GET().build();
        String httpContent = HttpClient.newHttpClient()
                .send(httpGet, HttpResponse.BodyHandlers.ofString()).body();
        String serverLogs = nacos.execInContainer("sh", "-c",
                "tail -c 3000 /home/nacos/logs/config-server.log 2>/dev/null; "
                        + "tail -c 2000 /home/nacos/logs/nacos-cluster.log 2>/dev/null; "
                        + "tail -c 2000 /home/nacos/logs/start.out 2>/dev/null").getStdout();
        if (!"from-nacos".equals(fetched)) {
            throw new IllegalStateException("诊断: fetched=" + fetched
                    + " | httpGet=" + httpContent
                    + " | serverStatus=" + configService.getServerStatus()
                    + " | serverLogs=" + serverLogs);
        }
        assertThat(fetched).contains("from-nacos");

        // B1 关键坑位：@DynamicPropertySource 在 config-data 解析阶段不可见（晚于环境准备），
        // 必须用系统属性让 spring.cloud.nacos.config.* 在 import 解析期生效
        // （host 模式下地址固定 localhost:8848，gRPC 推算 +1000 天然成立）
        System.setProperty("NACOS_CONFIG_ENABLED", "true");
        System.setProperty("NACOS_ADDR", "localhost:8848");
    }

    /** readiness 轮询（host 模式 + 日志等待后仍需确认 HTTP 可用）。 */
    private static void waitReadiness() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest probe = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8848/nacos/v1/console/health/readiness"))
                        .timeout(Duration.ofSeconds(3))
                        .GET().build();
                HttpResponse<String> resp = HttpClient.newHttpClient()
                        .send(probe, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Nacos readiness 60s 未就绪");
    }

    @AfterAll
    static void stopNacos() {
        System.clearProperty("NACOS_CONFIG_ENABLED");
        System.clearProperty("NACOS_ADDR");
        if (nacos != null) {
            nacos.stop();
        }
    }

    @Test
    @DisplayName("真实 Nacos 回路：远程属性加载 + 远程覆盖本地 application.yml 同名项")
    void remoteConfigLoadedAndOverridesLocal() {
        // 远程新增属性
        assertThat(env.getProperty("openforge.config.probe")).isEqualTo("from-nacos");
        // 远程覆盖本地（application.yml 中 server.tomcat.threads.max=20）
        assertThat(env.getProperty("server.tomcat.threads.max", Integer.class)).isEqualTo(7);
        // 远程未覆盖的本地属性保持不变（import 不吞并本地文件）
        assertThat(env.getProperty("spring.application.name")).isEqualTo("openforge-auth");
    }
}
