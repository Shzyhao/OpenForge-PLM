package com.openforge.material.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.client.NumberClient;
import com.openforge.material.dto.CreatePartRequest;
import com.openforge.material.dto.PageResponse;
import com.openforge.material.dto.UpdatePartRequest;
import com.openforge.material.entity.Part;
import com.openforge.material.entity.PartCategory;
import com.openforge.material.mapper.PartMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 物料主数据（M2-1：草稿 CRUD + 自动取号；状态机流转 M2-2 随发布流程交付）。 */
@Service
@RequiredArgsConstructor
public class PartService {

    /** 物料默认取号规则（V5 已内置，可在 auth 编号规则管理中调整段定义） */
    static final String NUMBER_RULE_KEY = "part";

    private final PartMapper partMapper;
    private final CategoryService categoryService;
    private final NumberClient numberClient;

    public Part create(CreatePartRequest request) {
        PartCategory category = categoryService.requireCategory(request.getCategoryId());
        String partNumber = numberClient.next(NUMBER_RULE_KEY);

        Part part = new Part();
        part.setPartNumber(partNumber);
        part.setName(request.getName());
        part.setNameEn(request.getNameEn());
        part.setType(request.getType());
        part.setCategoryId(category.getId());
        part.setAttrs(request.getAttrs());
        part.setUnit(request.getUnit());
        part.setLifecycleState("DRAFT");
        part.setVersion("A/1");
        part.setSecurityLevel("PUBLIC");
        part.setTenantId(0L);
        part.setDeleted(0);
        partMapper.insert(part);
        return part;
    }

    public Part detail(Long id) {
        Part part = partMapper.selectById(id);
        if (part == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "物料不存在");
        }
        return part;
    }

    public PageResponse<Part> page(long page, long pageSize, Long categoryId, String name, String type,
                                   String lifecycleState) {
        LambdaQueryWrapper<Part> wrapper = new LambdaQueryWrapper<Part>()
                .orderByDesc(Part::getId);
        if (categoryId != null) {
            PartCategory category = categoryService.requireCategory(categoryId);
            List<Long> categoryIds = categoryService.selfAndDescendantIds(category);
            wrapper.in(Part::getCategoryId, categoryIds);
        }
        if (name != null && !name.isBlank()) {
            wrapper.like(Part::getName, name.trim());
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Part::getType, type);
        }
        if (lifecycleState != null && !lifecycleState.isBlank()) {
            wrapper.eq(Part::getLifecycleState, lifecycleState);
        }
        Page<Part> result = partMapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 仅草稿可编辑（非草稿需走变更流程，开发文档 3.3）。 */
    public Part updateDraft(Long id, UpdatePartRequest request) {
        Part part = requireDraft(id);
        if (request.getName() != null && !request.getName().isBlank()) {
            part.setName(request.getName());
        }
        if (request.getNameEn() != null) {
            part.setNameEn(request.getNameEn());
        }
        if (request.getUnit() != null) {
            part.setUnit(request.getUnit());
        }
        if (request.getAttrs() != null) {
            part.setAttrs(request.getAttrs());
        }
        partMapper.updateById(part);
        return part;
    }

    /** 仅草稿可删除。 */
    public void deleteDraft(Long id) {
        requireDraft(id);
        partMapper.deleteById(id);
    }

    private Part requireDraft(Long id) {
        Part part = detail(id);
        if (!"DRAFT".equals(part.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "仅草稿状态的物料可编辑/删除，已发布物料请走变更流程");
        }
        return part;
    }
}
