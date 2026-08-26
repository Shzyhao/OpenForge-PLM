package com.openforge.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 自动配置：引入本模块即获得 @RequirePermission 校验能力。
 * 业务服务需保证自身无冲突的 WebMvcConfigurer（addInterceptors 可共存）。
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenForgeSecurityProperties.class)
public class OpenForgeSecurityAutoConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public PermissionQueryClient permissionQueryClient(OpenForgeSecurityProperties properties) {
        return new PermissionQueryClient(properties);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ModuleAvailabilityClient moduleAvailabilityClient(OpenForgeSecurityProperties properties) {
        return new ModuleAvailabilityClient(properties);
    }

    @Bean
    public WebMvcConfigurer openForgePermissionInterceptorRegistrar(PermissionQueryClient client) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new PermissionInterceptor(client))
                        .addPathPatterns("/api/**");
            }
        };
    }
}
