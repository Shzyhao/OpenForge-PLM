package com.openforge.auth.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 固定 admin 账号引导（方案 A1）：全新安装时为占位密码的 admin 生成随机初始密码并打印启动日志，
 * 首登强制改密（first_login_change=1）。已有密码的 admin 不打扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    static final String ADMIN_USERNAME = "admin";
    static final String PENDING_HASH = "PENDING_BOOTSTRAP";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        SysUser admin = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, ADMIN_USERNAME));
        if (admin == null) {
            log.error("固定 admin 账号缺失，请检查 V14 迁移");
            return;
        }
        if (!PENDING_HASH.equals(admin.getPasswordHash())) {
            return; // 已有密码（全新安装首次启动后会走到这里）
        }
        String initialPassword = "Of@" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        admin.setPasswordHash(passwordEncoder.encode(initialPassword));
        admin.setPasswordUpdatedAt(LocalDateTime.now());
        admin.setFirstLoginChange(1);
        userMapper.updateById(admin);
        log.warn("==============================================================");
        log.warn(" OpenForge 固定管理员账号初始化（仅此一次展示）:");
        log.warn("   用户名: admin    初始密码: {}", initialPassword);
        log.warn("   首次登录将强制修改密码。请立即登录并修改！");
        log.warn("==============================================================");
    }
}
