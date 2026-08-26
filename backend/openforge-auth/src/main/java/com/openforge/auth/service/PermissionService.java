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
    private final SecurityLogService securityLogService;

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

    /**
     * 幂等创建权限点（内部接口用，F2 发布流水线可重试）：已存在则复用；
     * 可选按角色编码绑定（不存在绑定才插入，不覆盖角色既有权限）。
     *
     * @return true = 本次新建；false = 已存在复用
     */
    @Transactional
    public boolean ensurePermission(String permCode, String permName, List<String> bindRoleCodes) {
        SysPermission existing = permissionMapper.selectOne(
                new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getPermCode, permCode));
        boolean created = existing == null;
        SysPermission permission = existing != null ? existing
                : createPermission(permCode, permName);
        if (bindRoleCodes != null && !bindRoleCodes.isEmpty()) {
            List<SysRole> roles = roleMapper.selectList(
                    new LambdaQueryWrapper<SysRole>().in(SysRole::getRoleCode, bindRoleCodes));
            for (SysRole role : roles) {
                Long bound = rolePermissionMapper.selectCount(
                        new LambdaQueryWrapper<SysRolePermission>()
                                .eq(SysRolePermission::getRoleId, role.getId())
                                .eq(SysRolePermission::getPermissionId, permission.getId()));
                if (bound == 0) {
                    SysRolePermission rp = new SysRolePermission();
                    rp.setRoleId(role.getId());
                    rp.setPermissionId(permission.getId());
                    rolePermissionMapper.insert(rp);
                }
            }
        }
        if (created) {
            securityLogService.audit(null, "PERM_CREATE", "PERMISSION", permCode,
                    "内部接口创建权限点" + (bindRoleCodes == null || bindRoleCodes.isEmpty()
                            ? "" : " 并绑定角色: " + bindRoleCodes));
        }
        return created;
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
        securityLogService.audit(null, "PERM_BIND", "ROLE",
                String.valueOf(roleId), "覆盖式绑定权限: " + permissionIds.size() + " 项");
    }

    /** 权限树（方案 C3）：菜单（MENU，含预留 children）+ 操作（OPERATION，按模块排序）。 */
    public java.util.Map<String, Object> permissionTree() {
        List<com.openforge.auth.entity.SysPermission> all = permissionMapper.selectList(
                new LambdaQueryWrapper<com.openforge.auth.entity.SysPermission>()
                        .orderByAsc(com.openforge.auth.entity.SysPermission::getSortOrder));
        List<com.openforge.auth.entity.SysPermission> menus = all.stream()
                .filter(p -> "MENU".equals(p.getPermType())).toList();
        List<com.openforge.auth.entity.SysPermission> operations = all.stream()
                .filter(p -> "OPERATION".equals(p.getPermType())).toList();
        return java.util.Map.of("menus", menus, "operations", operations);
    }

    /** 用户可见菜单编码（方案 F2 动态菜单）：SUPER 全量，其余按角色菜单权限。 */
    public List<String> menuCodesOfUser(com.openforge.auth.entity.SysUser user) {
        if ("SUPER".equals(user.getUserType())) {
            return permissionMapper.selectList(
                            new LambdaQueryWrapper<com.openforge.auth.entity.SysPermission>()
                                    .eq(com.openforge.auth.entity.SysPermission::getPermType, "MENU"))
                    .stream().map(com.openforge.auth.entity.SysPermission::getPermCode).toList();
        }
        return getPermissionCodesOfUser(user.getId()).stream()
                .filter(c -> c.startsWith("menu:")).toList();
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
