package com.openforge.connector.security;

import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 出站防护单测：空白名单全拒 / 白名单命中 / 私网拒绝 / 端口匹配。 */
class EgressGuardTest {

    @Test
    @DisplayName("白名单未配置 = 拒绝一切出站（安全默认值）")
    void emptyWhitelistBlocksAll() {
        EgressGuard guard = new EgressGuard("", false);
        assertThatCode(() -> guard.check("https://erp.example.com/api"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("白名单精确 host 命中；未命中拒绝；大小写不敏感")
    void whitelistMatching() {
        EgressGuard guard = new EgressGuard("erp.example.com, mes.example.com:9443", true);
        assertThatCode(() -> guard.check("https://ERP.EXAMPLE.COM/api")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("http://erp.example.com:8080/api")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("https://mes.example.com:9443/api")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("https://other.example.com/api"))
                .isInstanceOf(BizException.class);
        // mes.example.com 带端口语义：9443 之外的端口不匹配
        assertThatCode(() -> guard.check("https://mes.example.com/api"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("非 http/https 与缺 host 拒绝")
    void schemeAndHost() {
        EgressGuard guard = new EgressGuard("a.com", true);
        assertThatCode(() -> guard.check("ftp://a.com/file")).isInstanceOf(BizException.class);
        assertThatCode(() -> guard.check("https://")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("私网/回环地址默认拒绝（allowPrivate=false），DNS 解析逐一校验")
    void privateBlockedByDefault() {
        EgressGuard guard = new EgressGuard("localhost,127.0.0.1", false);
        // localhost 解析为 127.0.0.1（回环）→ 拒绝
        assertThatCode(() -> guard.check("http://localhost:8080/api"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("私网");
    }

    @Test
    @DisplayName("allowPrivate=true 时回环地址可出站（测试环境路径）")
    void privateAllowedWhenExplicit() {
        EgressGuard guard = new EgressGuard("localhost,127.0.0.1", true);
        assertThatCode(() -> guard.check("http://127.0.0.1:18080/api")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("URL 模板占位符（路径/查询）可过校验；host 位占位符不命中白名单被拒")
    void urlPlaceholderTolerated() {
        EgressGuard guard = new EgressGuard("erp.example.com", true);
        // P2-2 触发入参渲染进 URL：{{code}} 在查询位不阻断（运行时先渲染再请求）
        assertThatCode(() -> guard.check("https://erp.example.com/api?code={{code}}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.check("https://erp.example.com/docs/{{docId}}/export"))
                .doesNotThrowAnyException();
        // host 位占位符替换为哑元 host，无法命中白名单 → 拒绝（安全语义不变）
        assertThatCode(() -> guard.check("https://{{host}}.example.com/api"))
                .isInstanceOf(BizException.class);
    }
}
