package com.openforge.mono;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * mono 多 Flyway 编排（mono 设计 §3.2）：8 个服务在独立部署时各持一个 Flyway
 * 自动配置（locations/table/baseline 各异），单 classpath 下 spring.flyway.* 无法表达，
 * 且同名根资源目录已目录化为 db/migration/&lt;svc&gt;/——故按模块逐一显式建实例。
 * 历史表名与独立部署完全一致（对既有库零迁移语义变更）；auth 未配 table → 默认
 * flyway_schema_history（描述符已对齐，见 module/auth.yml）。
 * spring.flyway.enabled=false（application.yml），仅在 mono 装配路径生效。
 * target 可经 openforge.mono.flyway.target.&lt;svc&gt; 覆盖（H2 测试跳过 PG 专属迁移，
 * 等价各服务独立测试的 spring.flyway.target）。
 */
@Configuration
public class MonoFlywayConfig {

    private Flyway base(DataSource ds, String svc, String table, String target) {
        var configure = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/" + svc)
                .table(table)
                // mono 共享 schema：8 实例创建顺序不定，后跑者必见"非空 schema 且无自己的历史表"，
                // 必须容 baseline（真库已有各历史表 → baseline 不触发，语义零变更）
                .baselineOnMigrate(true)
                .baselineVersion("0");
        if (target != null && !target.isBlank()) {
            configure.target(target);
        }
        return configure.load();
    }

    @Bean(initMethod = "migrate")
    Flyway authFlyway(DataSource ds,
                      @Value("${openforge.mono.flyway.target.auth:}") String target) {
        return base(ds, "auth", "flyway_schema_history", target);
    }

    @Bean(initMethod = "migrate")
    Flyway materialFlyway(DataSource ds,
                          @Value("${openforge.mono.flyway.target.material:}") String target) {
        return base(ds, "material", "flyway_material_history", target);
    }

    @Bean(initMethod = "migrate")
    Flyway docFlyway(DataSource ds,
                     @Value("${openforge.mono.flyway.target.doc:}") String target) {
        return base(ds, "doc", "flyway_doc_history", target);
    }

    @Bean(initMethod = "migrate")
    Flyway workflowFlyway(DataSource ds,
                          @Value("${openforge.mono.flyway.target.workflow:}") String target) {
        return base(ds, "workflow", "flyway_workflow_history", target);
    }

    @Bean(initMethod = "migrate")
    Flyway changeFlyway(DataSource ds,
                        @Value("${openforge.mono.flyway.target.change:}") String target) {
        return base(ds, "change", "flyway_change_history", target);
    }

    @Bean(initMethod = "migrate")
    Flyway knowledgeFlyway(DataSource ds,
                           @Value("${openforge.mono.flyway.target.knowledge:}") String target) {
        return base(ds, "knowledge", "flyway_knowledge_history", target);
    }

    @Bean(initMethod = "migrate")
    Flyway projectFlyway(DataSource ds,
                         @Value("${openforge.mono.flyway.target.project:}") String target) {
        return base(ds, "project", "flyway_project_history", target);
    }

    @Bean(initMethod = "migrate")
    Flyway metadataFlyway(DataSource ds,
                          @Value("${openforge.mono.flyway.target.metadata:}") String target) {
        return base(ds, "metadata", "flyway_metadata_history", target);
    }
}
