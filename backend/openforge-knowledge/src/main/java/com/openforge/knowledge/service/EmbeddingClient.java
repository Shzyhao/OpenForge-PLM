package com.openforge.knowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Embedding 客户端：在线走 OpenAI 兼容 /embeddings；离线降级为词袋哈希向量
 * （中文 2-gram 分词，256 维归一化——检索质量有限但管道完整可测；生产接 pgvector/Milvus + 真实模型）。
 */
@Component
public class EmbeddingClient {

    public static final int DIM = 256;

    private final RestClient restClient;
    private final String model;
    private final boolean online;

    public EmbeddingClient(@Value("${openforge.llm.base-url:}") String baseUrl,
                           @Value("${openforge.llm.api-key:}") String apiKey,
                           @Value("${openforge.llm.embedding-model:embedding-3}") String embeddingModel) {
        this.online = !baseUrl.isBlank() && !apiKey.isBlank();
        this.model = embeddingModel;
        this.restClient = online ? RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/$", ""))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build() : null;
    }

    public boolean online() {
        return online;
    }

    public float[] embed(String text) {
        if (online) {
            try {
                Map<String, Object> body = Map.of("model", model, "input", List.of(text));
                EmbeddingResponse resp = restClient.post()
                        .uri("/embeddings")
                        .body(body)
                        .retrieve()
                        .body(EmbeddingResponse.class);
                if (resp != null && resp.data != null && !resp.data.isEmpty()) {
                    return toFloats(resp.data.get(0).embedding);
                }
            } catch (Exception ignored) {
                // 网络异常降级到词袋哈希
            }
        }
        return bagOfWords(text);
    }

    /** 离线词袋哈希：中文按 2-gram、英文按单词，哈希落桶后归一化。 */
    public static float[] bagOfWords(String text) {
        float[] v = new float[DIM];
        if (text == null || text.isBlank()) {
            return v;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (String.valueOf(c).matches("[\\u4e00-\\u9fa5]")) {
                tokens.add(String.valueOf(c));
                if (latin.length() > 0) {
                    tokens.add(latin.toString());
                    latin.setLength(0);
                }
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(c);
            } else if (latin.length() > 0) {
                tokens.add(latin.toString());
                latin.setLength(0);
            }
        }
        if (latin.length() > 0) {
            tokens.add(latin.toString());
        }
        // 组 2-gram（中文语义主要来自双字词）
        List<String> grams = new ArrayList<>(tokens);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            grams.add(tokens.get(i) + tokens.get(i + 1));
        }
        for (String g : grams) {
            v[Math.floorMod(g.hashCode(), DIM)] += 1f;
        }
        float norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIM; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    private static float[] toFloats(List<Double> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }

    private static class EmbeddingResponse {
        public List<Item> data;

        public static class Item {
            public List<Double> embedding;
        }
    }
}
