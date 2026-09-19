package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.connector.entity.ConnTriggerDlq;
import com.openforge.connector.mapper.ConnTriggerDlqMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 死信积压可观测性指标（R15 告警规则配套）：PENDING 死信数暴露为 Gauge，
 * Prometheus 告警（OpenForgeDlqBacklog）直接消费。回调即查（抓取间隔 15s，count 走索引，轻量）。
 */
@Component
@RequiredArgsConstructor
public class DlqMetrics {

    private final ConnTriggerDlqMapper dlqMapper;
    private final MeterRegistry registry;

    @PostConstruct
    void register() {
        Gauge.builder("openforge_connector_dlq_pending", this,
                m -> m.dlqMapper.selectCount(new LambdaQueryWrapper<ConnTriggerDlq>()
                        .eq(ConnTriggerDlq::getStatus, "PENDING")).doubleValue())
                .description("待人工处理的 PENDING 死信数")
                .register(registry);
    }
}
