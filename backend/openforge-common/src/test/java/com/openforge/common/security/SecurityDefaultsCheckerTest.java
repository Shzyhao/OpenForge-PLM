package com.openforge.common.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 安全默认值检测（v1.28 P2#14）+ 弱密钥长度校验（v1.30 A2）。 */
class SecurityDefaultsCheckerTest {

    private static final String STRONG = "s".repeat(40);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(SecurityDefaultsChecker.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(SecurityDefaultsChecker.class)).detachAppender(appender);
    }

    @Test
    @DisplayName("仓库默认值：告警命中（既有行为防回归）")
    void defaultSecretWarns() {
        run(prodEnv(SecurityDefaultsChecker.DEFAULT_JWT_SECRET, STRONG, STRONG));
        assertThat(warnTexts()).anyMatch(t -> t.contains("openforge.jwt.secret 仍在使用仓库默认值"));
    }

    @Test
    @DisplayName("非默认但 <32 字节：长度告警，不误报默认值")
    void shortSecretWarns() {
        run(prodEnv("x".repeat(31), STRONG, STRONG));
        assertThat(warnTexts()).anyMatch(t -> t.contains("openforge.jwt.secret 长度不足：31 字节 < 32 字节"));
        assertThat(warnTexts()).noneMatch(t -> t.contains("仍在使用仓库默认值"));
    }

    @Test
    @DisplayName("internal-token 两键位同样受长度校验")
    void shortInternalTokenWarns() {
        run(prodEnv(STRONG, "t".repeat(16), "t".repeat(16)));
        assertThat(warnTexts())
                .anyMatch(t -> t.contains("openforge.security.internal-token 长度不足：16 字节"))
                .anyMatch(t -> t.contains("openforge.internal.token 长度不足：16 字节"));
    }

    @Test
    @DisplayName("全部 ≥32 字节：零告警")
    void strongSecretsSilent() {
        run(prodEnv(STRONG, STRONG, STRONG));
        assertThat(warnTexts()).isEmpty();
    }

    @Test
    @DisplayName("dev/未设 profile：不打扰（既有行为防回归）")
    void nonProdSilent() {
        MockEnvironment dev = new MockEnvironment()
                .withProperty("openforge.jwt.secret", SecurityDefaultsChecker.DEFAULT_JWT_SECRET);
        dev.setActiveProfiles("dev");
        run(dev);
        run(new MockEnvironment().withProperty("openforge.jwt.secret", "short")); // 无 active profile
        assertThat(warnTexts()).isEmpty();
    }

    private void run(MockEnvironment env) {
        new SecurityDefaultsChecker(env).onApplicationEvent(null);
    }

    private MockEnvironment prodEnv(String jwt, String callerToken, String authToken) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("openforge.jwt.secret", jwt)
                .withProperty("openforge.security.internal-token", callerToken)
                .withProperty("openforge.internal.token", authToken);
        env.setActiveProfiles("prod");
        return env;
    }

    private List<String> warnTexts() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
