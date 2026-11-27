package com.openforge.auth.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 固定 admin 账号引导（方案 A1）：全新安装时为占位密码的 admin 写入初始密码（仅哈希存库，明文不落盘）。
 * 初始密码可按部署环境单独设置（环境变量 ADMIN_INITIAL_PASSWORD，或配置项
 * openforge.security.admin-initial-password / Nacos 远程配置），未设置时沿用公开默认值
 * （与冒烟脚本 smoke.sh 一致）——生产部署必须显式设置，且无论哪种来源都不回显到日志。
 * 首登强制改密（first_login_change=1）保留。已有密码的 admin 不打扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    static final String ADMIN_USERNAME = "admin";
    static final String PENDING_HASH = "PENDING_BOOTSTRAP";

    /** 未单独设置时沿用的默认初始密码（公开值，与冒烟脚本一致；生产部署必须通过环境变量覆盖） */
    static final String DEFAULT_INITIAL_PASSWORD = "smoke-test-2026";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${openforge.security.admin-initial-password:smoke-test-2026}")
    private String configuredPassword;

    @Override
    public void run(ApplicationArguments args) {
        SysUser admin = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, ADMIN_USERNAME));
        if (admin == null) {
            log.error("固定 admin 账号缺失，请检查 V14 迁移");
            return;
        }
        if (!PENDING_HASH.equals(admin.getPasswordHash())) {
            return; // 已有密码（不打扰）
        }
        String configured = configuredPassword == null ? "" : configuredPassword.trim();
        boolean usingDefault = configured.isEmpty() || DEFAULT_INITIAL_PASSWORD.equals(configured);
        String initialPassword = usingDefault ? DEFAULT_INITIAL_PASSWORD : configured;
        admin.setPasswordHash(passwordEncoder.encode(initialPassword));
        admin.setPasswordUpdatedAt(LocalDateTime.now());
        admin.setFirstLoginChange(1);
        userMapper.updateById(admin);
        log.warn("==============================================================");
        log.warn(" OpenForge 固定管理员账号初始化（仅此一次提示）:");
        log.warn("   用户名: admin    初始密码来源: {}（不回显明文）", usingDefault
                ? "未单独设置，沿用公开默认值（与冒烟脚本 smoke.sh 一致）"
                : "环境变量 ADMIN_INITIAL_PASSWORD / 配置项显式设置");
        if (usingDefault) {
            log.warn("   注意：当前使用公开默认密码，生产部署必须通过环境变量 ADMIN_INITIAL_PASSWORD 单独设置！");
        }
        log.warn("   首次登录将强制修改密码。请立即登录并修改！");
        log.warn("==============================================================");
    }
}
