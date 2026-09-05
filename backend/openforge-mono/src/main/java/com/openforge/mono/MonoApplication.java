package com.openforge.mono;

import com.openforge.auth.AuthApplication;
import com.openforge.auth.config.WebMvcConfig;
import com.openforge.change.ChangeApplication;
import com.openforge.common.module.ModuleRegistrar;
import com.openforge.doc.DocApplication;
import com.openforge.auth.interceptor.PermissionInterceptor;
import com.openforge.knowledge.KnowledgeApplication;
import com.openforge.material.MaterialApplication;
import com.openforge.metadata.MetadataApplication;
import com.openforge.project.ProjectApplication;
import com.openforge.workflow.WorkflowApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * mono 单进程模式（刀 1 骨架，docs/OpenForge-mono单进程设计.md §3.2）：
 * 8 个服务（auth + 7 业务）聚合为一个 servlet 上下文（:8090），gateway 仍独立 JVM。
 *
 * 扫描排除项（均为「独立部署语义」在 mono 下不成立的部分）：
 * - 8 个服务 Application 类：各自携带 @MapperScan，由本类统一多包扫描替代；
 * - ModuleRegistrar（common 组件扫描单例）：由 {@link MonoModuleRegistrarsConfig}
 *   按模块描述符建 8 实例（各带 60s 心跳、serviceUri 统一指向本进程端口）；
 * - auth WebMvcConfig + 库直查版 PermissionInterceptor：与 security 自动配置的
 *   权限拦截器重复挂载 /api/**（双重鉴权），mono 统一走 security 单一路径。
 *
 * 内部调用零改造（刀 1 边界）：13 处 RestClient 调用面原样保留，base-url 指向本进程
 * 回环（见 application.yml），与独立部署的 servlet 链语义同构。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.openforge", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                AuthApplication.class, MaterialApplication.class, DocApplication.class,
                WorkflowApplication.class, ChangeApplication.class, KnowledgeApplication.class,
                ProjectApplication.class, MetadataApplication.class,
                ModuleRegistrar.class, WebMvcConfig.class, PermissionInterceptor.class,
        })})
@MapperScan({
        "com.openforge.auth.mapper", "com.openforge.material.mapper", "com.openforge.doc.mapper",
        "com.openforge.workflow.mapper", "com.openforge.change.mapper", "com.openforge.knowledge.mapper",
        "com.openforge.project.mapper", "com.openforge.metadata.mapper",
})
public class MonoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonoApplication.class, args);
    }
}
