package com.openforge.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EventPublisher（B2-1）：关闭态契约（回退信号）、熔断、信封由消费侧测试覆盖。 */
class EventPublisherTest {

    @Test
    @DisplayName("默认关闭：publish 返回 false（调用方回退同步 HTTP），且不创建任何 MQ 客户端连接")
    void disabledModeReturnsFalse() {
        EventPublisher publisher = new EventPublisher(
                new ObjectMapper(), false, "localhost:9876", 3000, "test-svc");
        assertThat(publisher.publish("openforge-meta", "schema.migrated", Map.of("k", "v"))).isFalse();
        // 关闭态重复调用安全（无 producer 创建、无异常）
        assertThat(publisher.publish("openforge-meta", "schema.migrated", Map.of())).isFalse();
    }

    @Test
    @DisplayName("启用但 broker 不可达：发送失败返回 false + 熔断 60s（连续 3 次后快速失败，不拖垮发布路径）")
    void enabledWithUnreachableBrokerFallsBackWithCircuitBreaker() {
        EventPublisher publisher = new EventPublisher(
                new ObjectMapper(), true, "localhost:1", 300, "test-svc");
        // 前两次：真连（超时快速失败）
        assertThat(publisher.publish("openforge-object", "object.record.created", Map.of("id", 1))).isFalse();
        assertThat(publisher.publish("openforge-object", "object.record.created", Map.of("id", 2))).isFalse();
        // 第三次起熔断：立即 false（耗时应远小于 send timeout）
        long start = System.currentTimeMillis();
        assertThat(publisher.publish("openforge-object", "object.record.created", Map.of("id", 3))).isFalse();
        assertThat(System.currentTimeMillis() - start).isLessThan(200);
    }
}
