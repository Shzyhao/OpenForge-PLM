package com.openforge.common.web;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 内部服务间调用的 RestClient 工厂：统一注入 connect/read 超时。
 * 背景：{@code RestClient.builder()} 裸建走 JDK HttpClient 默认无超时，
 * 下游实例假死时调用方线程无限挂起并放大到上游事务/线程池（v1.27 P1#1）。
 * 默认 connect 3s / read 10s，适合内网同步调用；外呼慢速服务（如 LLM）用 {@link #builder(Duration)} 覆写 read。
 */
public final class InternalRestClientFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private InternalRestClientFactory() {
    }

    /** 默认超时（connect 3s / read 10s）的 builder，调用方继续追加 baseUrl/header 等。 */
    public static RestClient.Builder builder() {
        return builder(DEFAULT_READ_TIMEOUT);
    }

    /** 指定 read 超时的 builder（connect 恒为 3s）。 */
    public static RestClient.Builder builder(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
}
