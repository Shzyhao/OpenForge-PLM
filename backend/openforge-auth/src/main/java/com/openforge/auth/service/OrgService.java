package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.dto.OrgNodeResponse;
import com.openforge.auth.dto.UserBriefResponse;
import com.openforge.auth.entity.SysOrg;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.OrgMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrgService {

    private final OrgMapper orgMapper;
    private final UserMapper userMapper;

    @Transactional
    public SysOrg createOrg(String orgCode, String orgName, Long parentId, Integer sortOrder) {
        Long existing = orgMapper.selectCount(
                new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getOrgCode, orgCode));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.ORG_CODE_ALREADY_EXISTS);
        }
        SysOrg parent = null;
        if (parentId != null) {
            parent = orgMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException(ErrorCode.ORG_NOT_FOUND);
            }
        }
        SysOrg org = new SysOrg();
        org.setOrgCode(orgCode);
        org.setOrgName(orgName);
        org.setParentId(parentId);
        org.setSortOrder(sortOrder == null ? 0 : sortOrder);
        org.setStatus("ACTIVE");
        org.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        org.setPath("/"); // NOT NULL 占位，插入取得 id 后立即回填真实物化路径
        orgMapper.insert(org);

        String path = (parent == null ? "/" : parent.getPath()) + org.getId() + "/";
        org.setPath(path);
        orgMapper.updateById(org);
        return org;
    }

    /** 全量查询内存组树（组织规模通常在千级以内，M1 足够；超大规模再改增量加载）。 */
    public List<OrgNodeResponse> fullTree() {
        List<SysOrg> all = orgMapper.selectList(null);
        Map<Long, List<OrgNodeResponse>> byParent = all.stream()
                .map(OrgNodeResponse::of)
                .collect(Collectors.groupingBy(n -> n.parentId() == null ? -1L : n.parentId()));
        byParent.values().forEach(children ->
                children.sort(Comparator.comparing(OrgNodeResponse::sortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder()))));
        List<OrgNodeResponse> roots = byParent.getOrDefault(-1L, List.of());
        attachChildren(roots, byParent);
        return roots;
    }

    /** 递归挂 children（分组后建立父子引用）。 */
    private void attachChildren(List<OrgNodeResponse> roots, Map<Long, List<OrgNodeResponse>> byParent) {
        for (OrgNodeResponse node : roots) {
            List<OrgNodeResponse> children = byParent.getOrDefault(node.id(), List.of());
            node.children().addAll(children);
            attachChildren(children, byParent);
        }
    }

    @Transactional
    public SysOrg updateOrg(Long id, String orgName, Integer sortOrder) {
        SysOrg org = requireOrg(id);
        if (orgName != null && !orgName.isBlank()) {
            org.setOrgName(orgName);
        }
        if (sortOrder != null) {
            org.setSortOrder(sortOrder);
        }
        orgMapper.updateById(org);
        return org;
    }

    /** 移动组织到新父节点：防环校验 + 子树物化路径重算。 */
    @Transactional
    public void moveOrg(Long id, Long newParentId) {
        SysOrg org = requireOrg(id);
        SysOrg newParent = requireOrg(newParentId);
        if (newParent.getPath().startsWith(org.getPath())) {
            // 覆盖"移到自身下"与"移到自己的子树下"两种环
            throw new BizException(ErrorCode.ORG_MOVE_CYCLE);
        }
        String oldPath = org.getPath();
        String newPath = newParent.getPath() + org.getId() + "/";
        org.setParentId(newParentId);
        org.setPath(newPath);
        orgMapper.updateById(org);

        // 重算所有后代的前缀
        List<SysOrg> descendants = orgMapper.selectList(
                new LambdaQueryWrapper<SysOrg>().likeRight(SysOrg::getPath, oldPath)
                        .ne(SysOrg::getId, id));
        for (SysOrg d : descendants) {
            d.setPath(newPath + d.getPath().substring(oldPath.length()));
            orgMapper.updateById(d);
        }
    }

    @Transactional
    public void deleteOrg(Long id) {
        SysOrg org = requireOrg(id);
        Long children = orgMapper.selectCount(
                new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException(ErrorCode.ORG_HAS_CHILDREN);
        }
        Long users = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getOrgId, id));
        if (users != null && users > 0) {
            throw new BizException(ErrorCode.ORG_HAS_USERS);
        }
        orgMapper.deleteById(org.getId());
    }

    @Transactional
    public void assignUserOrg(Long userId, Long orgId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        if (orgId != null) {
            requireOrg(orgId);
        }
        user.setOrgId(orgId);
        userMapper.updateById(user);
    }

    /** 按组织查用户简表；includeChildren 时按物化路径前缀扩展到全部下级（ABAC 基础查询）。 */
    public List<UserBriefResponse> listUsers(Long orgId, boolean includeChildren) {
        SysOrg org = requireOrg(orgId);
        List<Long> orgIds;
        if (includeChildren) {
            orgIds = orgMapper.selectList(new LambdaQueryWrapper<SysOrg>()
                            .likeRight(SysOrg::getPath, org.getPath()))
                    .stream().map(SysOrg::getId).toList();
        } else {
            orgIds = List.of(org.getId());
        }
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getOrgId, orgIds))
                .stream()
                .map(u -> new UserBriefResponse(u.getId(), u.getUsername(), u.getDisplayName(), u.getOrgId()))
                .toList();
    }

    private SysOrg requireOrg(Long id) {
        SysOrg org = orgMapper.selectById(id);
        if (org == null) {
            throw new BizException(ErrorCode.ORG_NOT_FOUND);
        }
        return org;
    }
}
