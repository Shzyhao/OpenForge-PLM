package com.openforge.mono;

import com.openforge.common.module.ModuleRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * mono 模块注册编排：独立部署时每服务经 common 组件扫描持有一个 ModuleRegistrar
 * （读各自描述符，已目录化为 module/&lt;svc&gt;.yml）；mono 单 classpath 下描述符互相
 * 遮蔽，故按模块显式建 8 实例——各自加载自己的描述符、serviceUri 统一指向本进程
 * 实际端口（网关侧所有前缀 → mono 单 upstream，路由/自检/DEGRADED 机制不变），
 * 心跳各自 60s 上报（ApplicationListener/@Scheduled 对 @Bean 实例同样生效）。
 * baseUrl/serviceUri 均延迟到注册时解析（Supplier + local.server.port，兼容随机端口测试）。
 * 被扫描排除的原始组件见 MonoApplication。
 */
@Configuration
public class MonoModuleRegistrarsConfig {

    private ModuleRegistrar registrar(String descriptor, Environment env,
                                      String authBaseUrl, String internalToken) {
        return new ModuleRegistrar(authBaseUrl, internalToken, descriptor,
                () -> env.getProperty("local.server.port", env.getProperty("server.port", "8090")));
    }

    @Bean
    public ModuleRegistrar authRegistrar(Environment env,
                                         @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                         @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/auth.yml", env, authBaseUrl, internalToken);
    }

    @Bean
    public ModuleRegistrar materialRegistrar(Environment env,
                                             @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                             @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/material.yml", env, authBaseUrl, internalToken);
    }

    @Bean
    public ModuleRegistrar docRegistrar(Environment env,
                                        @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                        @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/doc.yml", env, authBaseUrl, internalToken);
    }

    @Bean
    public ModuleRegistrar workflowRegistrar(Environment env,
                                             @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                             @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/workflow.yml", env, authBaseUrl, internalToken);
    }

    @Bean
    public ModuleRegistrar changeRegistrar(Environment env,
                                           @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                           @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/change.yml", env, authBaseUrl, internalToken);
    }

    @Bean
    public ModuleRegistrar knowledgeRegistrar(Environment env,
                                              @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                              @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/knowledge.yml", env, authBaseUrl, internalToken);
    }

    @Bean
    public ModuleRegistrar projectRegistrar(Environment env,
                                            @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                            @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/project.yml", env, authBaseUrl, internalToken);
    }

    @Bean
    public ModuleRegistrar metadataRegistrar(Environment env,
                                             @Value("${openforge.security.auth-base-url}") String authBaseUrl,
                                             @Value("${openforge.security.internal-token}") String internalToken) {
        return registrar("module/metadata.yml", env, authBaseUrl, internalToken);
    }
}
