package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.dto.LoginRequest;
import com.openforge.auth.dto.RegisterRequest;
import com.openforge.auth.dto.TokenResponse;
import com.openforge.auth.dto.UserCreatedResponse;
import com.openforge.auth.entity.SysRole;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityLogService securityLogService;

    @Value("${openforge.security.open-registration:false}")
    private boolean openRegistration;

    @Value("${openforge.security.password-expiry-days:180}")
    private int passwordExpiryDays;

    @Value("${openforge.security.password-expiring-soon-days:7}")
    private int expiringSoonDays;

    @Value("${openforge.security.login-max-failures:5}")
    private int maxFailures;

    @Value("${openforge.security.login-lock-minutes:15}")
    private int lockMinutes;

    public UserCreatedResponse register(RegisterRequest request) {
        if (!openRegistration) {
            // 用户由管理员手动创建（方案 D8）；保留接口以便内测环境通过配置打开
            throw new BizException(ErrorCode.FORBIDDEN, "系统未开放自助注册，请联系管理员创建账号");
        }
        String username = request.getUsername();
        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName() == null ? username : request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setStatus("ACTIVE");
        user.setUserType("NORMAL");
        user.setPasswordUpdatedAt(java.time.LocalDateTime.now());
        user.setFirstLoginChange(0);
        user.setFailedLoginCount(0);
        user.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());  // 注册随请求租户
        user.setDeleted(0);
        userMapper.insert(user);

        log.info("user registered: id={}, username={}", user.getId(), username);
        return new UserCreatedResponse(user.getId(), username);
    }

    public TokenResponse login(LoginRequest request, String ip, String userAgent) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));

        // 登录失败锁定（方案 E9）：锁定期间直接拒绝
        if (user != null && user.getLockedUntil() != null
                && user.getLockedUntil().isAfter(java.time.LocalDateTime.now())) {
            securityLogService.recordLogin(request.getUsername(), false, "LOCKED", ip, userAgent);
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            if (user != null) {
                recordLoginFailure(user);
            }
            securityLogService.recordLogin(request.getUsername(), false, "BAD_CREDENTIALS", ip, userAgent);
            // 统一模糊提示，不暴露"用户不存在/密码错误"的区别
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            securityLogService.recordLogin(request.getUsername(), false, "DISABLED", ip, userAgent);
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        // 成功：清零失败计数
        if (user.getFailedLoginCount() != null && user.getFailedLoginCount() > 0) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            userMapper.updateById(user);
        }

        String token = jwtService.generate(user.getId(), user.getUsername(), user.getDisplayName(), user.getTenantId());
        String[] status = passwordStatus(user);
        securityLogService.recordLogin(request.getUsername(), true, status[0], ip, userAgent);
        return TokenResponse.of(token, jwtService.getTtlMinutes(), status[0],
                "EXPIRING_SOON".equals(status[0]) ? Long.parseLong(status[1]) : null);
    }

    /** 密码状态三态（方案 E2）：FORCE_CHANGE / EXPIRED / EXPIRING_SOON(含天数) / OK。 */
    private String[] passwordStatus(SysUser user) {
        if (user.getFirstLoginChange() != null && user.getFirstLoginChange() == 1) {
            return new String[]{"FORCE_CHANGE", "0"};
        }
        java.time.LocalDateTime updated = user.getPasswordUpdatedAt();
        if (updated == null) {
            return new String[]{"EXPIRED", "0"}; // 无时效记录按过期处理，强制补录
        }
        java.time.LocalDateTime expiry = updated.plusDays(passwordExpiryDays);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (expiry.isBefore(now)) {
            return new String[]{"EXPIRED", "0"};
        }
        java.time.LocalDateTime soonLine = expiry.minusDays(expiringSoonDays);
        if (soonLine.isBefore(now)) {
            long days = java.time.Duration.between(now, expiry).toDays() + 1;
            return new String[]{"EXPIRING_SOON", String.valueOf(days)};
        }
        return new String[]{"OK", "0"};
    }

    private void recordLoginFailure(SysUser user) {
        int failed = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
        user.setFailedLoginCount(failed);
        if (failed >= maxFailures) {
            user.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(lockMinutes));
            log.warn("account locked: {} for {} minutes after {} failures", user.getUsername(), lockMinutes, failed);
        }
        userMapper.updateById(user);
    }
}
