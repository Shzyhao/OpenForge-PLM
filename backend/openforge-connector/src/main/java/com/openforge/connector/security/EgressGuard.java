package com.openforge.connector.security;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 出站防护（集成编排器 MVP 设计 §7 SSRF 行）：
 * - 域白名单 OPENFORGE_CONNECTOR_EGRESS_WHITELIST（host 或 host:port，逗号分隔）——
 *   **未配置 = 拒绝一切出站**（刻意的安全默认值）；
 * - 私网地址拒绝（loopback/site-local/link-local/any-local），DNS 解析后逐一校验，
 *   防以域名解析到内网的方式绕过；测试环境可 openforge.connector.egress-allow-private=true 放开。
 * 白名单匹配在 DNS 解析前按 URL host 字符串比对（精确，大小写不敏感）。
 */
@Component
public class EgressGuard {

    private final Set<String> whitelist;
    private final boolean allowPrivate;

    public EgressGuard(
            @Value("${openforge.connector.egress-whitelist:}") String whitelistConfig,
            @Value("${openforge.connector.egress-allow-private:false}") boolean allowPrivate) {
        String config = whitelistConfig == null ? "" : whitelistConfig;
        this.whitelist = Arrays.stream(config.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.allowPrivate = allowPrivate;
    }

    public boolean configured() {
        return !whitelist.isEmpty();
    }

    /** 校验目标 URL；不通过抛 CONN_EGRESS_BLOCKED。 */
    public void check(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BizException(ErrorCode.CONN_EGRESS_BLOCKED, "仅允许 http/https 出站");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BizException(ErrorCode.CONN_EGRESS_BLOCKED, "目标地址缺少 host");
        }
        if (whitelist.isEmpty()) {
            throw new BizException(ErrorCode.CONN_EGRESS_BLOCKED,
                    "出站白名单未配置，已拦截（OPENFORGE_CONNECTOR_EGRESS_WHITELIST）");
        }
        if (!matches(host, uri.getPort(), scheme)) {
            throw new BizException(ErrorCode.CONN_EGRESS_BLOCKED, "目标 host 不在出站白名单内");
        }
        if (!allowPrivate) {
            checkNotPrivate(host);
        }
    }

    /**
     * 解析 URL：占位符 {{param}}（路径/查询模板）替换哑元后再解析——运行时先渲染再请求，
     * 校验时同样容忍模板。host 位占位符替换后为哑元 host，无法命中白名单（安全语义不变）。
     */
    private URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException ignoredFirst) {
            String probe = url.replaceAll("\\{\\{[a-zA-Z_][a-zA-Z0-9_]*}}", "param");
            try {
                return URI.create(probe);
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.CONN_EGRESS_BLOCKED, "目标地址无法解析");
            }
        }
    }

    /** host 精确匹配；白名单条目可带端口（缺省端口条目匹配该 scheme 默认端口与显式端口）。 */
    private boolean matches(String host, int port, String scheme) {
        String lower = host.toLowerCase(Locale.ROOT);
        int effective = port > 0 ? port
                : ("https".equals(scheme) ? 443 : 80);
        for (String entry : whitelist) {
            int colon = entry.lastIndexOf(':');
            if (colon > 0 && !entry.contains("]")) { // host:port（IPv6 字面量不含端口形态此处不支持）
                String entryHost = entry.substring(0, colon);
                int entryPort;
                try {
                    entryPort = Integer.parseInt(entry.substring(colon + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (entryHost.equals(lower) && entryPort == effective) {
                    return true;
                }
            } else if (entry.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private void checkNotPrivate(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CONN_EGRESS_BLOCKED, "目标 host 无法解析");
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw new BizException(ErrorCode.CONN_EGRESS_BLOCKED, "禁止出站到私网/回环地址");
            }
        }
    }
}
