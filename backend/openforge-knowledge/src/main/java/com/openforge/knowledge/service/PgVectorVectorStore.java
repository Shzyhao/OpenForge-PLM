package com.openforge.knowledge.service;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * pgvector 向量存储（M5 演进，架构文档 ADR-06）：SQL 级租户过滤（TopK 不被跨租户向量
 * 挤占）+ knowledge_item 行级拦截器双重隔离。余弦距离算子 &lt;=&gt;，HNSW 索引。
 * 维度约定：与 EmbeddingClient.DIM（256）一致，越界截断/不足补零在此边界处理。
 * 表与扩展程序化创建（@PostConstruct，不走 flyway——H2 测试不加载此 bean，
 * 且 vector 类型不可跨库移植）。生产也可由 DBA 预建。
 */
@Component
@ConditionalOnProperty(name = "openforge.knowledge.vector-store", havingValue = "pgvector")
public class PgVectorVectorStore implements VectorStore {

    public static final int DIM = 256;

    private final JdbcTemplate jdbc;

    public PgVectorVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE IF NOT EXISTS knowledge_embedding ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "vector_id VARCHAR(64) NOT NULL, "
                + "tenant_id BIGINT NOT NULL, "
                + "embedding vector(256) NOT NULL, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT uk_knowledge_embedding_vector UNIQUE (vector_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_tenant ON knowledge_embedding (tenant_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_hnsw ON knowledge_embedding USING hnsw (embedding vector_cosine_ops)");
    }

    @Override
    public String add(Long tenantId, float[] vector) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledge_embedding (vector_id, tenant_id, embedding) VALUES (?, ?, ?::vector)",
                id, tenantId, toVectorLiteral(vector));
        return id;
    }

    @Override
    public void remove(String vectorId) {
        if (vectorId != null) {
            jdbc.update("DELETE FROM knowledge_embedding WHERE vector_id = ?", vectorId);
        }
    }

    @Override
    public List<Scored> search(Long tenantId, float[] query, int topK) {
        // 余弦距离 <=> → score = 1 - distance（与内存实现语义一致）
        String literal = toVectorLiteral(query);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT vector_id, 1 - (embedding <=> ?::vector) AS score "
                        + "FROM knowledge_embedding WHERE tenant_id = ? "
                        + "ORDER BY embedding <=> ?::vector LIMIT ?",
                literal, tenantId, literal, topK);
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double score = ((Number) row.get("score")).doubleValue();
            if (score > 0.01) {
                scored.add(new Scored(String.valueOf(row.get("vector_id")), score));
            }
        }
        return scored;
    }

    /** 定维：截断/补零到 EmbeddingClient.DIM（在线模型维度可能不同）。 */
    private static String toVectorLiteral(float[] vector) {
        float[] fixed = new float[DIM];
        System.arraycopy(vector, 0, fixed, 0, Math.min(vector.length, DIM));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DIM; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(fixed[i]);
        }
        return sb.append(']').toString();
    }
}
