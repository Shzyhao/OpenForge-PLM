package com.openforge.auth.service;

import com.openforge.auth.dto.LoginRequest;
import com.openforge.auth.dto.RegisterRequest;
import com.openforge.auth.dto.TokenResponse;
import com.openforge.auth.dto.UserCreatedResponse;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userMapper,
                new BCryptPasswordEncoder(),
                new JwtService("unit-test-secret-key-32-bytes-abcdefgh", 120));
    }

    @Test
    @DisplayName("注册：重名应抛 USERNAME_ALREADY_EXISTS")
    void registerDuplicateUsernameShouldFail() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> authService.register(request("zhangsan", "password123")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("注册：入库密码必须是 BCrypt 密文而非明文")
    void registerShouldPersistHashedPassword() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysUser.class).setId(7L);
            return 1;
        });

        UserCreatedResponse resp = authService.register(request("zhangsan", "password123"));

        assertThat(resp.id()).isEqualTo(7L);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        org.mockito.Mockito.verify(userMapper).insert(captor.capture());
        SysUser saved = captor.getValue();
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(new BCryptPasswordEncoder().matches("password123", saved.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("登录：密码错误抛 BAD_CREDENTIALS，且不泄露用户是否存在")
    void loginWithWrongPasswordShouldFail() {
        SysUser user = persistedUser("zhangsan", new BCryptPasswordEncoder().encode("password123"));
        when(userMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> authService.login(login("zhangsan", "wrong-pass-1")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BAD_CREDENTIALS));
    }

    @Test
    @DisplayName("登录成功：返回可解析出 uid 的有效令牌")
    void loginSuccessShouldReturnValidToken() {
        SysUser user = persistedUser("zhangsan", new BCryptPasswordEncoder().encode("password123"));
        when(userMapper.selectOne(any())).thenReturn(user);

        TokenResponse resp = authService.login(login("zhangsan", "password123"));

        assertThat(resp.tokenType()).isEqualTo("Bearer");
        Claims claims = new JwtService("unit-test-secret-key-32-bytes-abcdefgh", 120).parse(resp.accessToken());
        assertThat(claims.getSubject()).isEqualTo("zhangsan");
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
    }

    private RegisterRequest request(String username, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }

    private LoginRequest login(String username, String password) {
        LoginRequest l = new LoginRequest();
        l.setUsername(username);
        l.setPassword(password);
        return l;
    }

    private SysUser persistedUser(String username, String passwordHash) {
        SysUser u = new SysUser();
        u.setId(42L);
        u.setUsername(username);
        u.setPasswordHash(passwordHash);
        u.setDisplayName(username);
        u.setStatus("ACTIVE");
        return u;
    }
}
