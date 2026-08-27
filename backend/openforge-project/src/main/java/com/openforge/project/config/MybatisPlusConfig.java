package com.openforge.project.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        // F3-1 多租户：tenant_id 行级自动过滤（架构文档 7.3）——全局表清单见 TenantTables，
        // 租户值来自网关 X-User-Tenant（TenantContext，默认 0 = 单租户部署行为不变）
        interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                    @Override
                    public net.sf.jsqlparser.expression.Expression getTenantId() {
                        return new net.sf.jsqlparser.expression.LongValue(
                                com.openforge.common.tenant.TenantContext.getTenantId());
                    }

                    @Override
                    public boolean ignoreTable(String tableName) {
                        return com.openforge.common.tenant.TenantTables.isGlobal(tableName);
                    }
                }));
        return interceptor;
    }
}
