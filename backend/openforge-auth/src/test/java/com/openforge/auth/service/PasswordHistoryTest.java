package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.dto.CreateUserRequest;
import com.openforge.auth.entity.SysPasswordHistory;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.PasswordHistoryMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 密码历史（方案 E8）：最近 3 次不可重复。 */
@ExtendWith(MockitoExtension.class)
class PasswordHistoryTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordHistoryMapper historyMapper;
    @Mock
    private SecurityLogService securityLogService;

    private UserAdminService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userMapper, null, null, null, encoder, historyMapper, securityLogService);
        ReflectionTestUtils.setField(service, "passwordExpiryDays", 180);
    }

    private SysUser user() {
        SysUser u = new SysUser();
        u.setId(5L);
        u.setUsername("histuser");
        u.setUserType("NORMAL");
        u.setStatus("ACTIVE");
        u.setFirstLoginChange(0);
        u.setFailedLoginCount(0);
        u.setPasswordHash(encoder.encode("Old12345"));
        u.setPasswordUpdatedAt(LocalDateTime.now());
        return u;
    }

    private SysPasswordHistory hash(String raw) {
        SysPasswordHistory h = new SysPasswordHistory();
        h.setUserId(5L);
        h.setPasswordHash(encoder.encode(raw));
        return h;
    }

    @Test
    @DisplayName("改密：与最近 3 次历史密码重复被拒")
    void reusedRecentPasswordRejected() {
        SysUser u = user();
        when(userMapper.selectById(5L)).thenReturn(u);
        when(historyMapper.selectList(any())).thenReturn(
                List.of(hash("Pass1111"), hash("Pass2222"), hash("Pass3333")));

        assertThatThrownBy(() -> service.changeMyPassword(5L, "Old12345", "Pass1111"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最近使用的密码重复");
        assertThatThrownBy(() -> service.changeMyPassword(5L, "Old12345", "Pass3333"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最近使用的密码重复");
    }

    @Test
    @DisplayName("改密：历史之外的新密码通过")
    void freshPasswordAccepted() {
        SysUser u = user();
        when(userMapper.selectById(5L)).thenReturn(u);
        when(historyMapper.selectList(any())).thenReturn(List.of(hash("Pass1111")));
        when(historyMapper.insert(any(SysPasswordHistory.class))).thenReturn(1);

        service.changeMyPassword(5L, "Old12345", "BrandNew99");
        // 新密码已入历史
        org.mockito.Mockito.verify(historyMapper).insert(any(SysPasswordHistory.class));
    }
}
