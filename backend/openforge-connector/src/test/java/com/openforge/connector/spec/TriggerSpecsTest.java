package com.openforge.connector.spec;

import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 触发配置校验矩阵（P2-2 §12.2）。 */
class TriggerSpecsTest {

    private static final Set<String> TOPICS = Set.of(
            "openforge-meta", "openforge-object", "openforge-doc",
            "openforge-change", "openforge-task", "openforge-connector");

    @Test
    @DisplayName("NONE：空配置规范化为空 Map")
    void noneNormalizesEmpty() {
        assertThat(TriggerSpecs.validateAndNormalize(null, null, TOPICS)).isEmpty();
        assertThat(TriggerSpecs.validateAndNormalize("NONE", null, TOPICS)).isEmpty();
        assertThat(TriggerSpecs.validateAndNormalize(null, Map.of(), TOPICS)).isEmpty();
    }

    @Test
    @DisplayName("NONE 携带配置应拒绝")
    void noneWithConfigRejected() {
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("NONE", Map.of("cron", "0 * * * * *"), TOPICS))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("未知 triggerType 拒绝")
    void unknownTypeRejected() {
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("WEBHOOK", Map.of(), TOPICS))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("EVENT：合法配置规范化 topic/tag，params 透传")
    void eventValid() {
        Map<String, Object> normalized = TriggerSpecs.validateAndNormalize("EVENT",
                Map.of("topic", "openforge-doc", "tag", "doc.released",
                        "params", Map.of("env", "prod")), TOPICS);
        assertThat(normalized).containsEntry("topic", "openforge-doc")
                .containsEntry("tag", "doc.released")
                .containsKey("params");
    }

    @Test
    @DisplayName("EVENT：白名单外 topic / 缺 topic / 非法 tag / 缺配置 全拒绝")
    void eventInvalid() {
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("EVENT",
                Map.of("topic", "part.released", "tag", "x"), TOPICS))  // material 主题 P3 未建
                .isInstanceOf(BizException.class).hasMessageContaining("白名单");
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("EVENT", Map.of("tag", "doc.released"), TOPICS))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("EVENT",
                Map.of("topic", "openforge-doc", "tag", "9bad"), TOPICS))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("EVENT", null, TOPICS))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("CRON：Spring 6 段表达式合法；秒位裸 * / 非法表达式 / 缺表达式 全拒绝")
    void cronValidation() {
        assertThat(TriggerSpecs.validateAndNormalize("CRON",
                Map.of("cron", "0 */5 * * * *"), TOPICS)).containsEntry("cron", "0 */5 * * * *");
        assertThat(TriggerSpecs.validateAndNormalize("CRON",
                Map.of("cron", "*/2 * * * * *"), TOPICS)).containsEntry("cron", "*/2 * * * * *");
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("CRON", Map.of("cron", "* * * * * *"), TOPICS))
                .isInstanceOf(BizException.class).hasMessageContaining("秒位");
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("CRON", Map.of("cron", "not-a-cron"), TOPICS))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("CRON", Map.of(), TOPICS))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("params 非对象拒绝")
    void paramsMustBeObject() {
        assertThatThrownBy(() -> TriggerSpecs.validateAndNormalize("CRON",
                Map.of("cron", "0 */5 * * * *", "params", "not-map"), TOPICS))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("运行时容错解析：非法库内配置降级 NONE；合法配置还原字段")
    void runtimeTolerantParse() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(TriggerSpecs.parseRuntime("CRON", "{broken", mapper).type())
                .isEqualTo(TriggerSpecs.TYPE_NONE);
        assertThat(TriggerSpecs.parseRuntime(null, null, mapper).type())
                .isEqualTo(TriggerSpecs.TYPE_NONE);
        TriggerSpecs.TriggerConfig cfg = TriggerSpecs.parseRuntime("EVENT",
                "{\"topic\":\"openforge-doc\",\"tag\":\"doc.released\",\"params\":{\"a\":1}}", mapper);
        assertThat(cfg.type()).isEqualTo("EVENT");
        assertThat(cfg.topic()).isEqualTo("openforge-doc");
        assertThat(cfg.tag()).isEqualTo("doc.released");
        assertThat(cfg.params()).containsEntry("a", 1);
    }
}
