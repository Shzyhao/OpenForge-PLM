package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysRole;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RbacService {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;

    public List<SysRole> listRoles() {
        return roleMapper.selectList(null);
    }

    public SysRole createRole(String roleCode, String roleName) {
        Long existing = roleMapper.selectCount(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.ROLE_CODE_ALREADY_EXISTS);
        }
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setBuiltin(0);
        role.setTenantId(0L);
        roleMapper.insert(role);
        return role;
    }

    /** 全量覆盖式分配：先清空再插入（事务内）。 */
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
            return;
        }
        Long validCount = roleMapper.selectCount(
                new LambdaQueryWrapper<SysRole>().in(SysRole::getId, roleIds));
        if (validCount == null || validCount != roleIds.stream().distinct().count()) {
            throw new BizException(ErrorCode.ROLE_NOT_FOUND);
        }
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        roleIds.stream().distinct().forEach(roleId -> {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        });
    }

    /** 用户信息 + 角色编码列表（/users/me 用）。用户不存在返回 null 由调用方决定响应。 */
    public List<String> getRoleCodesOfUser(Long userId) {
        List<SysUserRole> bindings = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = bindings.stream().map(SysUserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds).stream().map(SysRole::getRoleCode).toList();
    }

    public SysUser findUser(Long userId) {
        return userMapper.selectById(userId);
    }
}
