package com.openforge.connector.security;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定解析器单测（R6 SSRF 根治，v1.19.0）：DnsResolver.resolve 即 EgressGuard.resolveValidated——
 * 解析结果即建连地址，私网拒绝/IP 字面量校验/allowPrivate 语义在此钉住。
 * （白名单 URL 层语义由 EgressGuardTest 承接；连接时校验语义为运行时第二道防线。）
 */
class EgressPinningDnsResolverTest {

    @Test
    @DisplayName("allowPrivate=false：解析回环地址拒绝（重绑定落点 = 连接时拦截）")
    void loopbackBlockedAtResolveTime() {
        EgressGuard guard = new EgressGuard("localhost", false);
        EgressPinningDnsResolver resolver = new EgressPinningDnsResolver(guard);
        assertThatThrownBy(() -> resolver.resolve("localhost"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("私网");
    }

    @Test
    @DisplayName("allowPrivate=true：解析成功且返回真实地址（测试环境路径）")
    void loopbackAllowedWhenExplicit() throws Exception {
        EgressGuard guard = new EgressGuard("localhost", true);
        EgressPinningDnsResolver resolver = new EgressPinningDnsResolver(guard);
        InetAddress[] addresses = resolver.resolve("localhost");
        assertThat(addresses).isNotEmpty();
        assertThat(addresses[0].isLoopbackAddress()).isTrue();
    }

    @Test
    @DisplayName("私网 IP 字面量拒绝：站点本地/回环/链路本地（host 位直写内网 IP 的绕过路径）")
    void privateLiteralsBlocked() {
        EgressGuard guard = new EgressGuard("", true); // allowPrivate=true 仅放开校验；此处验 false
        EgressPinningDnsResolver strict = new EgressPinningDnsResolver(new EgressGuard("", false));
        for (String literal : new String[]{"192.168.1.10", "10.0.0.9", "127.0.0.1", "169.254.3.4"}) {
            assertThatThrownBy(() -> strict.resolve(literal))
                    .as(literal)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("私网");
        }
        // allowPrivate=true 时同一字面量放行
        assertThatCode(() -> new EgressPinningDnsResolver(guard).resolve("192.168.1.10"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("公网 IP 字面量放行；无法解析的 host 拒绝")
    void publicLiteralAndUnresolvable() {
        EgressPinningDnsResolver resolver = new EgressPinningDnsResolver(new EgressGuard("", false));
        assertThatCode(() -> resolver.resolve("93.184.216.34")).doesNotThrowAnyException();
        // RFC 2606 保留 TLD，保证不可解析 → 明确拒绝而非放行
        assertThatThrownBy(() -> resolver.resolve("nonexistent-host.invalid"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    @DisplayName("拒绝异常携带 CONN_EGRESS_BLOCKED（运行时落 BLOCKED 日志的契约）")
    void blockedErrorCode() {
        EgressPinningDnsResolver resolver = new EgressPinningDnsResolver(new EgressGuard("localhost", false));
        try {
            resolver.resolve("localhost");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONN_EGRESS_BLOCKED);
        }
    }
}
