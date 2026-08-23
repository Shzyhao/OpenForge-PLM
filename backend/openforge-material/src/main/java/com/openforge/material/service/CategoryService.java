package com.openforge.material.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.dto.CategoryNodeResponse;
import com.openforge.material.entity.PartCategory;
import com.openforge.material.mapper.PartCategoryMapper;
import com.openforge.material.mapper.PartMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 物料分类树：物化路径模式（同组织树，开发文档 7.1）。M2-1 提供建树与查询，移动/删除随属性模板一起交付。 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final PartCategoryMapper categoryMapper;
    private final PartMapper partMapper;

    @Transactional
    public PartCategory create(String categoryCode, String categoryName, Long parentId, Integer sortOrder) {
        Long existing = categoryMapper.selectCount(
                new LambdaQueryWrapper<PartCategory>().eq(PartCategory::getCategoryCode, categoryCode));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "分类编码已存在: " + categoryCode);
        }
        PartCategory parent = null;
        if (parentId != null) {
            parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "父分类不存在");
            }
        }
        PartCategory category = new PartCategory();
        category.setCategoryCode(categoryCode);
        category.setCategoryName(categoryName);
        category.setParentId(parentId);
        category.setSortOrder(sortOrder == null ? 0 : sortOrder);
        category.setTenantId(0L);
        category.setPath("/"); // NOT NULL 占位，插入后回填
        categoryMapper.insert(category);

        category.setPath((parent == null ? "/" : parent.getPath()) + category.getId() + "/");
        categoryMapper.updateById(category);
        return category;
    }

    public List<CategoryNodeResponse> tree() {
        List<PartCategory> all = categoryMapper.selectList(null);
        Map<Long, List<CategoryNodeResponse>> byParent = all.stream()
                .map(CategoryNodeResponse::of)
                .collect(Collectors.groupingBy(n -> n.parentId() == null ? -1L : n.parentId()));
        byParent.values().forEach(children ->
                children.sort(Comparator.comparing(CategoryNodeResponse::sortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder()))));
        List<CategoryNodeResponse> roots = byParent.getOrDefault(-1L, List.of());
        attach(roots, byParent);
        return roots;
    }

    /** 校验分类存在并返回；供物料创建使用。 */
    public PartCategory requireCategory(Long categoryId) {
        PartCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "物料分类不存在");
        }
        return category;
    }

    /** 分类及其全部子分类 id（物化路径前缀查询），供"按分类含子级"过滤。 */
    public List<Long> selfAndDescendantIds(PartCategory category) {
        return categoryMapper.selectList(new LambdaQueryWrapper<PartCategory>()
                        .likeRight(PartCategory::getPath, category.getPath()))
                .stream().map(PartCategory::getId).toList();
    }

    private void attach(List<CategoryNodeResponse> roots, Map<Long, List<CategoryNodeResponse>> byParent) {
        for (CategoryNodeResponse node : roots) {
            List<CategoryNodeResponse> children = byParent.getOrDefault(node.id(), List.of());
            node.children().addAll(children);
            attach(children, byParent);
        }
    }
}
