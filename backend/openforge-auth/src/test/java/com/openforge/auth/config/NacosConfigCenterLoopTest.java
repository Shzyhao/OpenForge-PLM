package com.openforge.auth.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 配置中心真实验证：真实 Nacos（standalone）→ 发布 openforge-auth.yml →
 * 应用启动经 optional:nacos import 加载 → 远程属性存在且覆盖本地同名项。
 *
 * 【v1.11.0 排障结论】历史 "publish true 但 get null" 的持久化怪癖根因 =
 * publish 确认与读可见性之间的竞态窗口：publishConfig 返回 true 后立即 getConfig
 * 会随机返回 null（独立探针零间隔连跑两次即一次 NULL 一次 OK；客户端 2.4.2 对
 * 服务端 v2.2.3/v2.3.2/v2.4.3、host 网络与端口映射、Linux CI 与 Windows 本地均复现，
 * 与版本错配/网络方案无关）。服务端持久化本身正常（v1 HTTP 交叉验证可读到）。
 * 修复 = getConfig 带退避重试；另 v2.3.2/v2.2.3 镜像存在 libtinfo.so.5 损坏、
 * 当前 Docker 无法启动，compose 随本次对齐 v2.4.3。
 *
 * 两种模式（NACOS_LOOP_TEST=true 才运行）：
 * - 复用模式：NACOS_ADDR 已设（如本地 NACOS=1 dev-up 起的 compose nacos），
 *   不另起容器，直接发布+验证（复用路径此前半残：提前 return 跳过配置发布）；
 * - 容器模式：Testcontainers 固定主机端口 8848/9848——Nacos 2.x 客户端从
 *   server-addr 端口推算 gRPC 端口（+1000），随机映射端口推算必然落空
 *   （CI 曾因此退到 host 网络模式）；固定同号映射在 Windows/Linux 通用。
 *
 * 本地调试：NACOS=1 ./scripts/dev-up.sh 起 compose nacos 后
 *   NACOS_LOOP_TEST=true NACOS_ADDR=localhost:8848 mvn -pl openforge-auth test
 * 默认关闭语义（NACOS_CONFIG_IMPORT 空 = import 完全跳过）由既有测试全绿隐式验证。
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("nacos-loop")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "NACOS_LOOP_TEST", matches = "true")
class NacosConfigCenterLoopTest {

    private static final String IMAGE = "nacos/nacos-server:v2.4.3";
    private static final String DATA_ID = "openforge-auth.yml";
    private static final String GROUP = "DEFAULT_GROUP";
    private static GenericContainer<?> nacos;

    @Autowired
    private Environment env;

    @BeforeAll
    static void prepareNacosAndPublishConfig() throws Exception {
        String addr = System.getProperty("NACOS_ADDR");
        if (addr == null || addr.isBlank()) {
            addr = System.getenv("NACOS_ADDR");
        }
        if (addr == null || addr.isBlank()) {
            boolean dockerAvailable;
            try {
                dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
            } catch (Exception e) {
                dockerAvailable = false;
            }
            Assumptions.assumeTrue(dockerAvailable, "Docker 不可用，跳过 Nacos 配置中心回路测试");
            startNacosContainer();
            addr = "localhost:8848";
        }
        waitReadiness(addr);
        publishAndVerifyConfig(addr);

        // B1 关键坑位：@DynamicPropertySource 在 config-data 解析阶段不可见（晚于环境准备），
        // 必须用系统属性在 import 解析期生效；import 本体由 nacos-loop profile 的
        // application-nacos-loop.yml 以 yml 级声明（占位符注入会被 import-check 判空，
        // 见排障记录），此处只开启用开关与地址
        System.setProperty("NACOS_CONFIG_ENABLED", "true");
        System.setProperty("NACOS_ADDR", addr);
    }

    /** 固定主机端口 8848/9848（gRPC +1000 推算成立）；本地已占 8848 请走 NACOS_ADDR 复用模式。 */
    @SuppressWarnings("deprecation")
    private static void startNacosContainer() {
        nacos = new FixedHostPortGenericContainer<>(IMAGE)
                .withEnv("MODE", "standalone")
                .withEnv("NACOS_AUTH_ENABLE", "false")
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m")
                .withFixedExposedPort(8848, 8848)
                .withFixedExposedPort(9848, 9848)
                .waitingFor(Wait.forLogMessage(".*Nacos started successfully.*", 1))
                .withStartupTimeout(Duration.ofSeconds(120));
        nacos.start();
    }

    /**
     * 启动前发布远程配置（openforge-auth.yml）——用与应用同栈的 Nacos 客户端
     * （nacos-client 2.4.2，即竞态窗口的探针本体），发布后 SDK 重试回读 +
     * v1 HTTP 交叉验证服务端确已持久化；失败携带全部诊断证据抛错。
     */
    private static void publishAndVerifyConfig(String addr) throws Exception {
        Properties props = new Properties();
        props.setProperty("serverAddr", addr);
        com.alibaba.nacos.api.config.ConfigService configService =
                com.alibaba.nacos.api.NacosFactory.createConfigService(props);
        String content = "openforge:\n  config:\n    probe: from-nacos\n"
                + "server:\n  tomcat:\n    threads:\n      max: 7\n";
        assertThat(configService.publishConfig(DATA_ID, GROUP, content)).isTrue();

        // 竞态窗口（见类注）：publish ack ≠ 读可见，立即 getConfig 随机 null → 带退避重试
        String fetched = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            fetched = configService.getConfig(DATA_ID, GROUP, 3000);
            if (fetched != null && fetched.contains("from-nacos")) {
                break;
            }
            Thread.sleep(300);
        }

        HttpRequest httpGet = HttpRequest.newBuilder()
                .uri(URI.create("http://" + addr + "/nacos/v1/cs/configs?dataId="
                        + URLEncoder.encode(DATA_ID, StandardCharsets.UTF_8)
                        + "&group=" + URLEncoder.encode(GROUP, StandardCharsets.UTF_8)))
                .GET().build();
        String httpContent = HttpClient.newHttpClient()
                .send(httpGet, HttpResponse.BodyHandlers.ofString()).body();
        String serverLogs = "";
        if (nacos != null) {
            serverLogs = nacos.execInContainer("sh", "-c",
                    "tail -c 3000 /home/nacos/logs/config-server.log 2>/dev/null; "
                            + "tail -c 2000 /home/nacos/logs/start.out 2>/dev/null").getStdout();
        }
        if (fetched == null || !fetched.contains("from-nacos")) {
            throw new IllegalStateException("诊断: fetched=" + fetched
                    + " | httpGet=" + httpContent
                    + " | serverStatus=" + configService.getServerStatus()
                    + " | serverLogs=" + serverLogs);
        }
    }

    /** readiness 轮询（复用/容器模式统一：日志等待不能替代 HTTP 可用确认）。 */
    private static void waitReadiness(String addr) throws Exception {
        String host = addr.split(":")[0];
        int port = Integer.parseInt(addr.split(":")[1]);
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest probe = HttpRequest.newBuilder()
                        .uri(URI.create("http://%s:%d/nacos/v1/console/health/readiness"
                                .formatted(host, port)))
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
        throw new IllegalStateException("Nacos readiness 60s 未就绪（" + addr + "）");
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
        // 远程未覆盖的本地属性保持不变（import 不吞并本地文件；test yml 整体遮蔽主 yml，
        // 主 yml 的 spring.application.name 在测试上下文不存在——取 test yml 独有项验证）
        assertThat(env.getProperty("openforge.security.open-registration", Boolean.class)).isTrue();
    }
}
