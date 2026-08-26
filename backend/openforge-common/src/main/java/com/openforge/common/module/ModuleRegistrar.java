package com.openforge.common.module;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/**
 * 模块注册器（A4 设计 3.2）：启动时扫描 classpath 的 openforge-module.yml 并上报 auth
 * 注册中心（幂等 upsert + 心跳）。无描述符的模块（如 common/security 纯库）自动空转。
 * 注册与心跳均为尽力而为：auth 不可用时告警不阻断业务服务启动，心跳周期重试。
 */
@Slf4j
@Component
public class ModuleRegistrar implements ApplicationListener<ApplicationReadyEvent> {

    private final ModuleDescriptor descriptor;
    private final RestClient restClient;
    private final String internalToken;
    private volatile boolean registered = false;

    public ModuleRegistrar(
            @Value("${openforge.security.auth-base-url:http://localhost:8081}") String authBaseUrl,
            @Value("${openforge.security.internal-token:openforge-internal-dev-token}") String internalToken) {
        this.internalToken = internalToken;
        this.restClient = RestClient.builder().baseUrl(authBaseUrl).build();
        this.descriptor = loadDescriptor();
    }

    private static ModuleDescriptor loadDescriptor() {
        try {
            ClassPathResource resource = new ClassPathResource(ModuleDescriptor.RESOURCE);
            if (!resource.exists()) {
                return null;
            }
            try (var in = resource.getInputStream()) {
                Map<String, Object> yaml = new Yaml().load(in);
                return ModuleDescriptor.parse(yaml);
            }
        } catch (Exception e) {
            throw new IllegalStateException("openforge-module.yml 解析失败", e);
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (descriptor == null) {
            log.debug("无模块描述符，跳过注册（纯库或未接入模块化）");
            return;
        }
        register("startup");
    }

    /** 心跳：60s 上报一次（A4 设计 3.2，超时 3 倍由注册中心判定 BROKEN）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void heartbeat() {
        if (descriptor != null) {
            register("heartbeat");
        }
    }

    private void register(String trigger) {
        try {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("moduleKey", descriptor.getModuleKey());
            body.put("moduleType", descriptor.getModuleType());
            body.put("displayName", descriptor.getDisplayName() == null ? descriptor.getModuleKey() : descriptor.getDisplayName());
            body.put("version", descriptor.getVersion() == null ? "0.0.0" : descriptor.getVersion());
            body.put("routes", descriptor.getRoutes());
            body.put("menu", descriptor.getMenu());
            body.put("dependencies", descriptor.getDependencies());
            body.put("flywayTable", descriptor.getFlywayTable() == null ? "" : descriptor.getFlywayTable());
            body.put("healthPath", descriptor.getHealth() == null ? "" : descriptor.getHealth());
            restClient.post()
                    .uri("/api/v1/internal/modules")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            registered = true;
            log.debug("模块注册心跳成功（{}）: {}", trigger, descriptor.getModuleKey());
        } catch (Exception e) {
            if ("startup".equals(trigger)) {
                log.warn("模块注册失败（将继续以心跳重试）: {} — {}", descriptor.getModuleKey(), e.getMessage());
            } else if (!registered) {
                log.debug("模块注册重试中: {} — {}", descriptor.getModuleKey(), e.getMessage());
            }
        }
    }
}
