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

    private static final String IMAGE = "nacos/nacos-server:v2.3.2";
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
        nacos = new GenericContainer<>(IMAGE)
                .withEnv("MODE", "standalone")
                .withEnv("NACOS_AUTH_ENABLE", "false")
                // Nacos 2.x 客户端走 gRPC（端口 = 8848+1000 = 9848），必须固定映射否则客户端静默连不上
                .withCreateContainerCmdModifier(cmd -> cmd.getPortBindings().add(
                        new com.github.dockerjava.api.model.PortBinding(
                                com.github.dockerjava.api.model.Ports.Binding.bindPort(9848),
                                new com.github.dockerjava.api.model.ExposedPort(9848))))
                .withExposedPorts(8848)
                .waitingFor(Wait.forHttp("/nacos/v1/console/health/readiness").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(120));
        nacos.start();

        // 启动前发布远程配置（openforge-auth.yml）：远程新增属性 + 覆盖本地同名属性
        String content = "openforge:\n  config:\n    probe: from-nacos\n"
                + "server:\n  tomcat:\n    threads:\n      max: 7\n";
        String form = "dataId=" + URLEncoder.encode("openforge-auth.yml", StandardCharsets.UTF_8)
                + "&group=" + URLEncoder.encode("DEFAULT_GROUP", StandardCharsets.UTF_8)
                + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8)
                + "&type=yaml";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + nacos.getHost() + ":" + nacos.getMappedPort(8848)
                        + "/nacos/v1/cs/configs"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.body()).isEqualTo("true");

        // B1 关键坑位：@DynamicPropertySource 在 config-data 解析阶段不可见（晚于环境准备），
        // 必须用系统属性让 spring.cloud.nacos.config.* 在 import 解析期生效
        System.setProperty("NACOS_CONFIG_ENABLED", "true");
        System.setProperty("NACOS_ADDR", nacos.getHost() + ":" + nacos.getMappedPort(8848));
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
