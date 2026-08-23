package com.openforge.material.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.client.NumberClient;
import com.openforge.material.dto.CreatePartRequest;
import com.openforge.material.dto.PageResponse;
import com.openforge.material.dto.UpdatePartRequest;
import com.openforge.material.entity.Part;
import com.openforge.material.entity.PartCategory;
import com.openforge.material.entity.PartVersion;
import com.openforge.material.mapper.PartMapper;
import com.openforge.material.mapper.PartVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 物料主数据：草稿 CRUD + 属性模板校验 + 状态机 + 发布版本快照。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartService {

    static final String NUMBER_RULE_KEY = "part";

    private final PartMapper partMapper;
    private final PartVersionMapper partVersionMapper;
    private final CategoryService categoryService;
    private final AttrTemplateService attrTemplateService;
    private final NumberClient numberClient;
    /** Spring 配置的 ObjectMapper（含 JavaTimeModule，支持 LocalDateTime 快照序列化） */
    private final ObjectMapper objectMapper;

    public Part create(CreatePartRequest request) {
        PartCategory category = categoryService.requireCategory(request.getCategoryId());
        attrTemplateService.validate(category.getAttrTemplate(), request.getAttrs());
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

    /** 状态分布统计（报表）。 */
    public java.util.Map<String, Long> stats() {
        return partMapper.selectList(new LambdaQueryWrapper<Part>().select(Part::getLifecycleState))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Part::getLifecycleState, java.util.stream.Collectors.counting()));
    }

    /** 仅草稿可编辑（非草稿走变更流程，开发文档 3.3）。 */
    public Part updateDraft(Long id, UpdatePartRequest request) {
        Part part = requireDraft(id);
        PartCategory category = categoryService.requireCategory(part.getCategoryId());
        String newAttrs = request.getAttrs() != null ? request.getAttrs() : part.getAttrs();
        attrTemplateService.validate(category.getAttrTemplate(), newAttrs);
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
            part.setAttrs(newAttrs);
        }
        partMapper.updateById(part);
        return part;
    }

    /** 仅草稿可删除。 */
    public void deleteDraft(Long id) {
        requireDraft(id);
        partMapper.deleteById(id);
    }

    // ===== 状态机（M3 由流程引擎驱动，M2 为轻量直接流转） =====

    /** DRAFT → REVIEWING */
    public Part submit(Long id, Long operatorId) {
        return transition(id, "REVIEWING", operatorId);
    }

    /** REVIEWING → DRAFT（驳回） */
    public Part reject(Long id, Long operatorId) {
        return transition(id, "DRAFT", operatorId);
    }

    /** REVIEWING → RELEASED，发布时固化版本快照。 */
    @Transactional
    public Part approve(Long id, Long operatorId) {
        Part part = transition(id, "RELEASED", operatorId);
        PartVersion version = new PartVersion();
        version.setPartId(part.getId());
        version.setVersion(part.getVersion());
        version.setState("RELEASED");
        version.setReleasedBy(operatorId);
        try {
            version.setSnapshot(objectMapper.writeValueAsString(part));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "快照序列化失败");
        }
        partVersionMapper.insert(version);
        log.info("part released: id={}, number={}, version={}", part.getId(), part.getPartNumber(), part.getVersion());
        return part;
    }

    private Part transition(Long id, String target, Long operatorId) {
        Part part = detail(id);
        StateMachine.requireTransition(StateMachine.PART, part.getLifecycleState(), target);
        part.setLifecycleState(target);
        part.setUpdatedBy(operatorId);
        partMapper.updateById(part);
        return part;
    }

    private Part requireDraft(Long id) {
        Part part = detail(id);
        if (!"DRAFT".equals(part.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "仅草稿状态的物料可编辑/删除，已发布物料请走变更流程");
        }
        return part;
    }
}
