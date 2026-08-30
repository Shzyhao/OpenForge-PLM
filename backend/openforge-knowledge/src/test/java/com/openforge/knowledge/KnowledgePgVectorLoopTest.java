package com.openforge.knowledge;

import com.openforge.common.tenant.TenantContext;
import com.openforge.knowledge.service.KnowledgeService;
import com.openforge.knowledge.dto.SearchHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1-M5 演进：pgvector 向量存储真实回路（Testcontainers，CI 真实执行/本机 Docker 关闭自动跳过）。
 * 真实 PG（pgvector/pgvector:pg16）+ flyway postgresql/V2（扩展+表+HNSW）+
 * vector-store=pgvector → 租户隔离断言：租户 7 写入，租户 7 检索命中、租户 8 检索为空
 * （SQL 级过滤 + knowledge_item 行级拦截器双保险）。
 */
@SpringBootTest
class KnowledgePgVectorLoopTest {

    private static PostgreSQLContainer<?> pg;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startPgIfDockerAvailable() {
        boolean docker;
        try {
            docker = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "Docker 不可用，跳过 pgvector 回路测试");
        pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
        pg.start();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry registry) {
        if (pg != null && pg.isRunning()) {
            registry.add("spring.datasource.url", pg::getJdbcUrl);
            registry.add("spring.datasource.username", pg::getUsername);
            registry.add("spring.datasource.password", pg::getPassword);
            registry.add("openforge.knowledge.vector-store", () -> "pgvector");
        }
    }

    private long createItem(Long tenantId, String title, String content) {
        TenantContext.setTenantId(tenantId);
        try {
            return knowledgeService.create(title, content, "pgvector,回路", "MANUAL", "loop-" + title, null).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private List<SearchHit> searchAs(Long tenantId, String query) {
        TenantContext.setTenantId(tenantId);
        try {
            return knowledgeService.search(query, 10);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("真实 pgvector 回路：租户隔离——各租户仅命中自己的文档")
    void tenantIsolatedVectorSearch() {
        Assumptions.assumeTrue(pg != null && pg.isRunning(), "PG 未启动");
        // 确认 pgvector 实现 + 扩展已装
        String ext = jdbc.queryForObject(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class);
        assertThat(ext).isEqualTo("vector");

        createItem(7L, "锻炉橙热处理规范", "45#钢调质处理工艺：淬火温度 840 度，回火 560 度，硬度 HRC 28-32。");
        createItem(8L, "铝合金阳极氧化规范", "6061 铝合金硫酸阳极氧化：电流密度 1.5 A/dm2，氧化时间 30 分钟。");

        // 租户 7：命中自己的文档，绝不出现租户 8 的文档（SQL 级租户过滤 + 行级双保险）
        List<SearchHit> hits7 = searchAs(7L, "淬火 回火 硬度");
        assertThat(hits7).extracting(SearchHit::title).contains("锻炉橙热处理规范");
        assertThat(hits7).extracting(SearchHit::title).doesNotContain("铝合金阳极氧化规范");

        // 租户 8：同理——不出现租户 7 的文档；可能命中自己的（离线词袋向量跨主题余弦非零）
        List<SearchHit> hits8 = searchAs(8L, "淬火 回火 硬度");
        assertThat(hits8).extracting(SearchHit::title).doesNotContain("锻炉橙热处理规范");

        // 租户 8 命中自己的文档
        List<SearchHit> hits8Own = searchAs(8L, "阳极氧化 电流密度");
        assertThat(hits8Own).extracting(SearchHit::title).contains("铝合金阳极氧化规范");
        assertThat(hits8Own).extracting(SearchHit::title).doesNotContain("锻炉橙热处理规范");
    }
}
