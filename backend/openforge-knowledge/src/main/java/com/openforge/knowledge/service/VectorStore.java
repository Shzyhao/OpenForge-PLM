package com.openforge.knowledge.service;

import java.util.List;

/**
 * 向量存储接口（M5 演进：pgvector 切换，架构文档 ADR-06）。
 * 租户感知：add/search 均携带 tenantId——pgvector 实现在 SQL 级过滤（TopK 不被跨租户
 * 向量挤占），叠加 knowledge_item 行级租户拦截器双重隔离（画像 §5 自检项）。
 */
public interface VectorStore {

    /** 写入向量，返回 vectorId（与 knowledge_item.vector_id 关联）。 */
    String add(Long tenantId, float[] vector);

    /** 删除向量（knowledge_item 删除/重嵌时调用；id 不存在时静默）。 */
    void remove(String vectorId);

    /** 余弦相似 TopK（输入向量已归一化，score ∈ [0,2]）。 */
    List<Scored> search(Long tenantId, float[] query, int topK);

    record Scored(String vectorId, double score) {
    }
}
