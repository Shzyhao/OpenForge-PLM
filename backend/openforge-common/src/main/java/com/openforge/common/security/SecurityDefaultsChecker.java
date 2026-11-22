package com.openforge.common.security;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 默认密钥检测（v1.28 P2#14）：internal-token / JWT secret 仍在使用仓库默认值时醒目告警。
 * 仅在**已显式设置 profile 且非 dev/test/local**（生产/预发形态）时提示——测试与本地裸跑不产生噪音。
 * 命中时打多行 WARN + openforge_default_secret_active 计数（Prometheus 侧可配告警）。
 * 长度校验（v1.30 A2）：已换非默认值但过短（&lt;32 字节）同样告警——换了弱钥与留默认值风险同源，
 * 阈值与 application.yml 注释"&gt;=32 字节"对齐（JWT HS256 密钥要求；internal-token 同理），
 * 计数 openforge_weak_secret_length。
 */
@Slf4j
@Component
public class SecurityDefaultsChecker implements ApplicationListener<ApplicationReadyEvent> {

    static final String DEFAULT_INTERNAL_TOKEN = "openforge-internal-dev-token";
    static final String DEFAULT_JWT_SECRET = "openforge-dev-secret-key-change-me-in-production-0001";
    /** 弱密钥长度阈值（UTF-8 字节） */
    static final int MIN_SECRET_BYTES = 32;

    private final Environment env;

    public SecurityDefaultsChecker(Environment env) {
        this.env = env;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (env.getActiveProfiles().length == 0
                || env.acceptsProfiles(Profiles.of("dev", "test", "local"))) {
            return; // 本地/测试形态：默认值是预期，不打扰
        }
        check("openforge.security.internal-token", env.getProperty("openforge.security.internal-token", ""),
                DEFAULT_INTERNAL_TOKEN, "服务间调用将可被伪造（任何知道默认 token 的调用方可直连内部端点）");
        check("openforge.internal.token", env.getProperty("openforge.internal.token", ""),
                DEFAULT_INTERNAL_TOKEN, "auth 接收方未换钥，与调用方默认值联动全仓 401 风险面同源");
        check("openforge.jwt.secret", env.getProperty("openforge.jwt.secret", ""),
                DEFAULT_JWT_SECRET, "任何人可自签合法 JWT 完全绕过认证");
    }

    private void check(String key, String actual, String defaultValue, String consequence) {
        if (defaultValue.equals(actual)) {
            Metrics.counter("openforge_default_secret_active", "key", key).increment();
            log.warn("╔════════ 安全配置告警 ════════");
            log.warn("║ {} 仍在使用仓库默认值！", key);
            log.warn("║ 后果：{}", consequence);
            log.warn("║ 处置：通过环境变量覆盖（见 application.yml 注释）后重启");
            log.warn("╚══════════════════════════════");
            return;
        }
        int length = actual.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            Metrics.counter("openforge_weak_secret_length", "key", key).increment();
            log.warn("╔════════ 安全配置告警 ════════");
            log.warn("║ {} 长度不足：{} 字节 < {} 字节！", key, length, MIN_SECRET_BYTES);
            log.warn("║ 后果：{}", consequence);
            log.warn("║ 处置：换用 ≥{} 字节随机值（环境变量覆盖）后重启", MIN_SECRET_BYTES);
            log.warn("╚══════════════════════════════");
        }
    }
}
