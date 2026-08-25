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

    @Value("${openforge.security.open-registration:false}")
    private boolean openRegistration;

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
        user.setTenantId(0L);
        user.setDeleted(0);
        userMapper.insert(user);

        log.info("user registered: id={}, username={}", user.getId(), username);
        return new UserCreatedResponse(user.getId(), username);
    }

    public TokenResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // 统一模糊提示，不暴露"用户不存在/密码错误"的区别
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        String token = jwtService.generate(user.getId(), user.getUsername(), user.getDisplayName());
        return TokenResponse.of(token, jwtService.getTtlMinutes());
    }
}
