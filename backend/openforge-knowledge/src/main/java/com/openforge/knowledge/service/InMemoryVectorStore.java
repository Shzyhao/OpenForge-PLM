package com.openforge.knowledge.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储（默认实现，H2 测试/单实例开发用；余弦相似 TopK）。
 * 租户分桶存储——search 仅在调用方租户桶内计算，与 pgvector 实现语义对齐。
 * 生产/多租户场景切 openforge.knowledge.vector-store=pgvector（SQL 级租户过滤）。
 */
@Component
@ConditionalOnProperty(name = "openforge.knowledge.vector-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private final Map<Long, Map<String, float[]>> vectorsByTenant = new ConcurrentHashMap<>();

    @Override
    public String add(Long tenantId, float[] vector) {
        String id = UUID.randomUUID().toString();
        vectorsByTenant.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>()).put(id, vector);
        return id;
    }

    @Override
    public void remove(String vectorId) {
        if (vectorId == null) {
            return;
        }
        vectorsByTenant.values().forEach(m -> m.remove(vectorId));
    }

    @Override
    public List<Scored> search(Long tenantId, float[] query, int topK) {
        Map<String, float[]> vectors = vectorsByTenant.getOrDefault(tenantId, Map.of());
        List<Scored> scored = new ArrayList<>();
        vectors.forEach((id, v) -> scored.add(new Scored(id, cosine(query, v))));
        return scored.stream()
                .filter(s -> s.score() > 0.01)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .toList();
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot; // 输入向量已归一化，点积即余弦
    }
}
