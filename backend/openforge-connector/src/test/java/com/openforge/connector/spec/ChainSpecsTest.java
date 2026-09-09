package com.openforge.connector.spec;

import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 链规格解析/校验矩阵（P3 刀1，设计 §14.3）。 */
class ChainSpecsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private Map<String, Object> httpStep(String key, String url) {
        return Map.of("key", key, "type", "HTTP_REST",
                "spec", Map.of("schemaVersion", 1, "method", "GET", "url", url));
    }

    @Test
    @DisplayName("isChain 判定：schemaVersion=2 为链；v1/解析失败非链")
    void isChainDetection() throws Exception {
        assertThat(ChainSpecs.isChain(Map.of("schemaVersion", 2, "steps", List.of()))).isTrue();
        assertThat(ChainSpecs.isChain(Map.of("schemaVersion", 1))).isFalse();
        assertThat(ChainSpecs.isChainJson("{\"schemaVersion\":2,\"steps\":[]}", MAPPER)).isTrue();
        assertThat(ChainSpecs.isChainJson("{\"schemaVersion\":1,\"method\":\"GET\"}", MAPPER)).isFalse();
        assertThat(ChainSpecs.isChainJson("{broken", MAPPER)).isFalse();
    }

    @Test
    @DisplayName("合法两步链解析：steps/branches typed 还原")
    void parseValidChain() {
        Map<String, Object> spec = Map.of(
                "schemaVersion", 2,
                "steps", List.of(
                        httpStep("fetch", "http://localhost:8080/a"),
                        Map.of("key", "load", "type", "HTTP_REST",
                                "spec", Map.of("schemaVersion", 1, "method", "GET",
                                        "url", "http://localhost:8080/b?token={{steps.fetch.body.t}}"),
                                "params", Map.of("x", 1), "continueOnError", true, "x", 5, "y", 6)),
                "branches", List.of(Map.of("from", "fetch", "to", "load", "expr", "#steps.fetch.httpStatus == 200")));
        ChainSpecs.Chain chain = ChainSpecs.parse(spec, MAPPER, null);
        assertThat(chain.steps()).hasSize(2);
        assertThat(chain.steps().get(1).key()).isEqualTo("load");
        assertThat(chain.steps().get(1).continueOnError()).isTrue();
        assertThat(chain.steps().get(1).x()).isEqualTo(5);
        assertThat(chain.branches()).hasSize(1);
    }

    @Test
    @DisplayName("校验拒绝矩阵：空 steps/超上限/key 重复非法/缺 spec/分支端点未声明/空 expr")
    void validationRejections() {
        Map<String, Object> base = Map.of("schemaVersion", 2,
                "steps", List.of(httpStep("a", "http://localhost:8080/a")));
        assertThatThrownBy(() -> ChainSpecs.parse(Map.of("schemaVersion", 2), MAPPER, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ChainSpecs.parse(
                Map.of("schemaVersion", 2, "steps", List.of()), MAPPER, null))
                .isInstanceOf(BizException.class);
        // 步数上限
        List<Map<String, Object>> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < ChainSpecs.MAX_STEPS + 1; i++) {
            tooMany.add(httpStep("s" + i, "http://localhost:8080/x"));
        }
        assertThatThrownBy(() -> ChainSpecs.parse(
                Map.of("schemaVersion", 2, "steps", tooMany), MAPPER, null))
                .isInstanceOf(BizException.class).hasMessageContaining("上限");
        // key 非法/重复
        assertThatThrownBy(() -> ChainSpecs.parse(Map.of("schemaVersion", 2,
                "steps", List.of(httpStep("Bad-Key", "http://localhost:8080/a"))), MAPPER, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ChainSpecs.parse(Map.of("schemaVersion", 2,
                "steps", List.of(httpStep("a", "http://localhost:8080/a"),
                        httpStep("a", "http://localhost:8080/b"))), MAPPER, null))
                .isInstanceOf(BizException.class).hasMessageContaining("重复");
        // 步骤缺 spec
        assertThatThrownBy(() -> ChainSpecs.parse(Map.of("schemaVersion", 2,
                "steps", List.of(Map.of("key", "a", "type", "HTTP_REST"))), MAPPER, null))
                .isInstanceOf(BizException.class);
        // 步骤 spec 非法（url 缺 host）
        assertThatThrownBy(() -> ChainSpecs.parse(Map.of("schemaVersion", 2,
                "steps", List.of(httpStep("a", "http://"))), MAPPER, null))
                .isInstanceOf(BizException.class);
        // 分支端点未声明 / 空 expr
        assertThatThrownBy(() -> ChainSpecs.parse(Map.of("schemaVersion", 2,
                "steps", List.of(httpStep("a", "http://localhost:8080/a")),
                "branches", List.of(Map.of("from", "a", "to", "ghost", "expr", "true"))), MAPPER, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ChainSpecs.parse(Map.of("schemaVersion", 2,
                "steps", List.of(httpStep("a", "http://localhost:8080/a"), httpStep("b", "http://localhost:8080/b")),
                "branches", List.of(Map.of("from", "a", "to", "b", "expr", ""))), MAPPER, null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("canonical 化：continueOnError=false/缺省坐标不输出；params 空省略")
    void normalizeOutput() {
        Map<String, Object> normalized = ChainSpecs.normalize(Map.of(
                "schemaVersion", 2,
                "steps", List.of(
                        httpStep("a", "http://localhost:8080/a"),
                        Map.of("key", "b", "type", "HTTP_REST",
                                "spec", Map.of("schemaVersion", 1, "method", "GET", "url", "http://localhost:8080/b"),
                                "params", Map.of(), "continueOnError", false, "x", 1))), MAPPER, null);
        assertThat(normalized.get("schemaVersion")).isEqualTo(2);
        List<Map<String, Object>> steps = castList(normalized.get("steps"));
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0)).doesNotContainKeys("params", "continueOnError", "x", "y");
        assertThat(steps.get(1)).doesNotContainKeys("params", "continueOnError", "y");
        assertThat(steps.get(1)).containsEntry("x", 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return (List<Map<String, Object>>) o;
    }
}
