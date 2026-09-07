package com.openforge.starter.data;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.openforge.common.tenant.TenantContext;
import com.openforge.common.tenant.TenantTables;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据层中央装配（A5 Starter 化）：分页 + 多租户拦截器一次定义、全部服务共享。
 * 替代此前各服务重复的 MybatisPlusConfig（8 份拷贝收敛为 1）；
 * 租户语义见 TenantContext/TenantTables——单租户部署（默认 0）行为不变。
 */
@Configuration
public class MybatisPlusAutoConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 官方要求顺序：多租户（改写 SQL）必须在分页之前——否则分页 count SQL 不携带
        // 租户条件，多租户下 total 虚高（连接器容器测试 CI 首跑实纱，单租户部署无感知）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(TenantContext.getTenantId());
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return TenantTables.isGlobal(tableName);
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
