package com.openforge.knowledge.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储（余弦相似 TopK）。M5 开发/单实例够用；
 * M6 按架构文档 ADR-06 切换 pgvector（小规模）或 Milvus（集群），接口不变。
 */
@Component
public class InMemoryVectorStore {

    public record Scored(String vectorId, double score) {
    }

    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    public String add(float[] vector) {
        String id = UUID.randomUUID().toString();
        vectors.put(id, vector);
        return id;
    }

    public void remove(String vectorId) {
        if (vectorId != null) {
            vectors.remove(vectorId);
        }
    }

    public List<Scored> search(float[] query, int topK) {
        return vectors.entrySet().stream()
                .map(e -> new Scored(e.getKey(), cosine(query, e.getValue())))
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
