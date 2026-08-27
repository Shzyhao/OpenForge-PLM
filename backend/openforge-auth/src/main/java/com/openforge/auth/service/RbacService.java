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
    private final com.openforge.auth.mapper.RolePermissionMapper rolePermissionMapper;
    private final SecurityLogService securityLogService;

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
        role.setEnabled(1);
        role.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        roleMapper.insert(role);
        return role;
    }

    /** 编辑角色（方案 B3）：名称/描述可改；内置角色编码不可变（无编码修改入口即天然满足）。 */
    public SysRole updateRole(Long id, String roleName, String description) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.ROLE_NOT_FOUND);
        }
        if (roleName != null && !roleName.isBlank()) {
            role.setRoleName(roleName);
        }
        if (description != null) {
            role.setDescription(description);
        }
        roleMapper.updateById(role);
        return role;
    }

    /** 删除角色（方案 B4）：内置角色与仍有成员绑定的角色拒绝删除。 */
    @Transactional
    public void deleteRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.ROLE_NOT_FOUND);
        }
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "内置角色不可删除");
        }
        Long members = userRoleMapper.selectCount(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        if (members != null && members > 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "角色仍有 " + members + " 名成员，请先移除后再删除");
        }
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<com.openforge.auth.entity.SysRolePermission>()
                        .eq(com.openforge.auth.entity.SysRolePermission::getRoleId, id));
        roleMapper.deleteById(id);
    }

    // ===== 成员管理（方案 B5） =====

    public List<SysUser> members(Long roleId) {
        requireRole(roleId);
        List<Long> userIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(userIds);
    }

    @Transactional
    public void addMembers(Long roleId, List<Long> userIds) {
        requireRole(roleId);
        for (Long userId : userIds) {
            SysUser user = userMapper.selectById(userId);
            if (user == null) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在: " + userId);
            }
            Long exists = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, userId).eq(SysUserRole::getRoleId, roleId));
            if (exists == null || exists == 0) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
    }

    @Transactional
    public void removeMember(Long roleId, Long userId) {
        requireRole(roleId);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId).eq(SysUserRole::getUserId, userId));
    }

    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.ROLE_NOT_FOUND);
        }
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
        securityLogService.audit(null, "ROLE_ASSIGN", "USER",
                String.valueOf(userId), "覆盖式分配角色: " + roleIds);
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
