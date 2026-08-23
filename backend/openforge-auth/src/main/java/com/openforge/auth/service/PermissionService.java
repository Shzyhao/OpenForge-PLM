package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysPermission;
import com.openforge.auth.entity.SysRole;
import com.openforge.auth.entity.SysRolePermission;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.PermissionMapper;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.RolePermissionMapper;
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
public class PermissionService {

    private final PermissionMapper permissionMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;

    public List<SysPermission> listPermissions() {
        return permissionMapper.selectList(null);
    }

    public SysPermission createPermission(String permCode, String permName) {
        Long existing = permissionMapper.selectCount(
                new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getPermCode, permCode));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.PERMISSION_CODE_ALREADY_EXISTS);
        }
        SysPermission p = new SysPermission();
        p.setPermCode(permCode);
        p.setPermName(permName);
        permissionMapper.insert(p);
        return p;
    }

    /** 覆盖式绑定角色的权限点（事务内）。 */
    @Transactional
    public void bindRolePermissions(Long roleId, List<Long> permissionIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.ROLE_NOT_FOUND);
        }
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        Long validCount = permissionMapper.selectCount(
                new LambdaQueryWrapper<SysPermission>().in(SysPermission::getId, permissionIds));
        if (validCount == null || validCount != permissionIds.stream().distinct().count()) {
            throw new BizException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        permissionIds.stream().distinct().forEach(pid -> {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pid);
            rolePermissionMapper.insert(rp);
        });
    }

    public List<String> getPermissionCodesOfRole(Long roleId) {
        List<SysRolePermission> bindings = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> permIds = bindings.stream().map(SysRolePermission::getPermissionId).toList();
        return permissionMapper.selectBatchIds(permIds).stream().map(SysPermission::getPermCode).toList();
    }

    /** 用户 → 角色 → 权限点 联查。M1 三段查询足够，热点优化（缓存/一条 join）随 M2 网关统一鉴权处理。 */
    public List<String> getPermissionCodesOfUser(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        List<SysRolePermission> rolePerms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds));
        if (rolePerms.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> permIds = rolePerms.stream().map(SysRolePermission::getPermissionId).distinct().toList();
        return permissionMapper.selectBatchIds(permIds).stream().map(SysPermission::getPermCode).toList();
    }
}
