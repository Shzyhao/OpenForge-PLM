package com.openforge.connector.security;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;

/**
 * 出站 HTTP 固定解析器（R6 SSRF 根治，v1.19.0）：把 EgressGuard 的"解析即校验"
 * 挂到 Apache HttpClient 5 的解析扩展点上——连接管理器每次建立连接经本解析器取地址，
 * EgressGuard 校验所用的解析结果与实际建连地址同源，DNS 重绑定（TOCTOU）窗口消除。
 * 白名单（URL 层）仍在设计态/执行入口校验，本层专守私网防线（重绑定攻击的落点）。
 */
public class EgressPinningDnsResolver implements DnsResolver {

    private final EgressGuard egressGuard;

    public EgressPinningDnsResolver(EgressGuard egressGuard) {
        this.egressGuard = egressGuard;
    }

    @Override
    public InetAddress[] resolve(String host) {
        return egressGuard.resolveValidated(host);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return resolve(host)[0].getCanonicalHostName();
    }
}
