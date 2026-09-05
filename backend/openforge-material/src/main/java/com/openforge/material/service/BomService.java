package com.openforge.material.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.client.NumberClient;
import com.openforge.material.dto.BomDiffResponse;
import com.openforge.material.dto.BomLineRequest;
import com.openforge.material.dto.BomLineResponse;
import com.openforge.material.dto.SubstituteRequest;
import com.openforge.material.dto.SubstituteUpdateRequest;
import com.openforge.material.entity.Bom;
import com.openforge.material.entity.BomLine;
import com.openforge.material.entity.BomLineSubstitute;
import com.openforge.material.entity.Part;
import com.openforge.material.mapper.BomLineMapper;
import com.openforge.material.mapper.BomLineSubstituteMapper;
import com.openforge.material.mapper.BomMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** BOM 管理（开发文档 3.1.2）：行维护（含替代组、行号）、多层展开（环检测）、反查、版本对比、升版、状态机。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomService {

    static final String NUMBER_RULE_KEY = "bom";

    private final BomMapper bomMapper;
    private final BomLineMapper bomLineMapper;
    private final BomLineSubstituteMapper substituteMapper;
    private final PartService partService;
    private final NumberClient numberClient;

    /** 单行替代件防呆上限（设计文档 §5.5） */
    @Value("${openforge.material.substitute.max-per-line:10}")
    private int maxSubstitutesPerLine;

    // ===== BOM 头 =====

    @Transactional
    public Bom create(Long parentPartId, Long operatorId) {
        Part parent = partService.detail(parentPartId);
        Bom bom = new Bom();
        bom.setBomNumber(numberClient.next(NUMBER_RULE_KEY));
        bom.setParentPartId(parent.getId());
        bom.setBomType("EBOM");
        bom.setVersion("A/1");
        bom.setLifecycleState("DRAFT");
        bom.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        bom.setCreatedBy(operatorId);
        bom.setDeleted(0);
        bomMapper.insert(bom);
        return bom;
    }

    public Bom requireBom(Long bomId) {
        Bom bom = bomMapper.selectById(bomId);
        if (bom == null) {
            throw new BizException(ErrorCode.BOM_NOT_FOUND);
        }
        return bom;
    }

    // ===== 行维护（仅草稿 BOM） =====

    public BomLine addLine(Long bomId, BomLineRequest request) {
        requireDraftBom(bomId);
        Part child = requireReferenceablePart(request.getChildPartId(), "BOM 行");
        List<BomLine> existing = lines(bomId);
        boolean dup = existing.stream().anyMatch(l -> l.getChildPartId().equals(request.getChildPartId()));
        if (dup) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "该子件已存在于 BOM 中");
        }
        BomLine line = new BomLine();
        line.setBomId(bomId);
        line.setChildPartId(request.getChildPartId());
        line.setPosition(nextPosition(existing));
        line.setQuantity(request.getQuantity());
        line.setRefDes(request.getRefDes());
        line.setUsageType(request.getUsageType() == null ? "NORMAL" : request.getUsageType());
        line.setEffectiveFrom(request.getEffectiveFrom());
        line.setEffectiveTo(request.getEffectiveTo());
        line.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        bomLineMapper.insert(line);
        return line;
    }

    public List<BomLine> lines(Long bomId) {
        return bomLineMapper.selectList(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getBomId, bomId).orderByAsc(BomLine::getPosition).orderByAsc(BomLine::getId));
    }

    /** 行视图（带子件信息、行号与替代组），GET /lines 与前端消费。 */
    public List<BomLineResponse> lineDetails(Long bomId) {
        requireBom(bomId);
        List<BomLine> ls = lines(bomId);
        Map<Long, List<BomLineSubstitute>> subs = substitutesByLineIds(lineIds(ls));
        List<BomLineResponse> result = new ArrayList<>(ls.size());
        for (BomLine line : ls) {
            Part child = partService.detail(line.getChildPartId());
            BomLineResponse row = new BomLineResponse();
            row.setId(line.getId());
            row.setBomId(line.getBomId());
            row.setPosition(line.getPosition());
            row.setChildPartId(child.getId());
            row.setChildPartNumber(child.getPartNumber());
            row.setChildPartName(child.getName());
            row.setQuantity(line.getQuantity());
            row.setRefDes(line.getRefDes());
            row.setUsageType(line.getUsageType());
            row.setEffectiveFrom(line.getEffectiveFrom());
            row.setEffectiveTo(line.getEffectiveTo());
            row.setAttrs(line.getAttrs());
            row.setSubstitutes(substituteViews(subs.getOrDefault(line.getId(), List.of())));
            result.add(row);
        }
        return result;
    }

    public void removeLine(Long bomId, Long lineId) {
        requireDraftBom(bomId);
        requireLine(bomId, lineId);
        substituteMapper.delete(new LambdaQueryWrapper<BomLineSubstitute>()
                .eq(BomLineSubstitute::getBomLineId, lineId));
        bomLineMapper.delete(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getBomId, bomId).eq(BomLine::getId, lineId));
        resequence(bomId);
    }

    // ===== 替代组（仅草稿 BOM；发布版走统一变更中心，刀 2） =====

    public List<BomLineResponse.SubstituteView> substitutes(Long bomId, Long lineId) {
        requireBom(bomId);
        requireLine(bomId, lineId);
        return substituteViews(substitutesOf(lineId));
    }

    @Transactional
    public BomLineSubstitute addSubstitute(Long bomId, Long lineId, SubstituteRequest request) {
        requireDraftBom(bomId);
        BomLine line = requireLine(bomId, lineId);
        Part sub = requireReferenceablePart(request.getSubstitutePartId(), "替代件");
        if (sub.getId().equals(line.getChildPartId())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "替代件不能与主件相同");
        }
        requireNotAncestor(bomId, sub);
        Long dup = substituteMapper.selectCount(new LambdaQueryWrapper<BomLineSubstitute>()
                .eq(BomLineSubstitute::getBomLineId, lineId)
                .eq(BomLineSubstitute::getSubstitutePartId, request.getSubstitutePartId()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "该替代件已存在于替代组中");
        }
        List<BomLineSubstitute> group = substitutesOf(lineId);
        if (group.size() >= maxSubstitutesPerLine) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "单行替代件数量已达上限（" + maxSubstitutesPerLine + "）");
        }
        if (request.getQtyCoefficient() != null
                && request.getQtyCoefficient().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "替代系数必须大于 0");
        }
        BomLineSubstitute entity = new BomLineSubstitute();
        entity.setBomLineId(lineId);
        entity.setSubstitutePartId(request.getSubstitutePartId());
        entity.setPriority(request.getPriority() != null ? request.getPriority() : nextPriority(group));
        entity.setQtyCoefficient(request.getQtyCoefficient() != null
                ? request.getQtyCoefficient() : BigDecimal.ONE);
        entity.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        substituteMapper.insert(entity);
        return entity;
    }

    @Transactional
    public BomLineSubstitute updateSubstitute(Long bomId, Long lineId, Long subId,
                                              SubstituteUpdateRequest request) {
        requireDraftBom(bomId);
        requireLine(bomId, lineId);
        BomLineSubstitute sub = requireSubstitute(lineId, subId);
        if (request.getPriority() == null && request.getQtyCoefficient() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "优先级与替代系数至少填一项");
        }
        if (request.getQtyCoefficient() != null
                && request.getQtyCoefficient().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "替代系数必须大于 0");
        }
        if (request.getPriority() != null) {
            sub.setPriority(request.getPriority());
        }
        if (request.getQtyCoefficient() != null) {
            sub.setQtyCoefficient(request.getQtyCoefficient());
        }
        substituteMapper.updateById(sub);
        return sub;
    }

    @Transactional
    public void removeSubstitute(Long bomId, Long lineId, Long subId) {
        requireDraftBom(bomId);
        requireLine(bomId, lineId);
        requireSubstitute(lineId, subId);
        substituteMapper.deleteById(subId);
    }

    // ===== 展开（含环检测） / 反查 =====

    /** 多层展开为树；展开过程中检测循环引用。level 为最大层数（1=单层）。 */
    public BomNode expand(Long bomId, int level) {
        Bom bom = requireBom(bomId);
        return expandNode(bom, level, new HashSet<>(Set.of(bom.getParentPartId())));
    }

    private BomNode expandNode(Bom bom, int level, Set<Long> pathAncestors) {
        Part parent = partService.detail(bom.getParentPartId());
        BomNode node = new BomNode(parent.getId(), parent.getPartNumber(), parent.getName(),
                BigDecimal.ONE, new ArrayList<>(), new ArrayList<>());
        if (level <= 0) {
            return node;
        }
        List<BomLine> lines = lines(bom.getId());
        Map<Long, List<BomLineSubstitute>> subs = substitutesByLineIds(lineIds(lines));
        for (BomLine line : lines) {
            if (pathAncestors.contains(line.getChildPartId())) {
                Part cyclePart = partService.detail(line.getChildPartId());
                throw new BizException(ErrorCode.BOM_CYCLE,
                        "循环引用: " + cyclePart.getPartNumber() + " 出现在自身祖先路径上");
            }
            Part child = partService.detail(line.getChildPartId());
            List<SubstituteNode> subNodes = subs.getOrDefault(line.getId(), List.of()).stream()
                    .map(s -> {
                        Part subPart = partService.detail(s.getSubstitutePartId());
                        return new SubstituteNode(subPart.getId(), subPart.getPartNumber(),
                                subPart.getName(), s.getPriority(), s.getQtyCoefficient());
                    })
                    .collect(Collectors.toList());
            BomNode childNode = new BomNode(child.getId(), child.getPartNumber(), child.getName(),
                    line.getQuantity(), subNodes, new ArrayList<>());
            node.children().add(childNode);
            // 递归展开子件自己的 BOM（草稿 BOM 也参与展开，便于设计期检查）
            List<Bom> childBoms = bomMapper.selectList(new LambdaQueryWrapper<Bom>()
                    .eq(Bom::getParentPartId, child.getId()));
            Set<Long> childPath = new HashSet<>(pathAncestors);
            childPath.add(child.getId());
            for (Bom childBom : childBoms) {
                BomNode deep = expandNode(childBom, level - 1, childPath);
                childNode.children().addAll(deep.children());
            }
        }
        return node;
    }

    /** 反查（Where-Used）：使用指定物料的 BOM 及其父件；usageRole 区分主件用法与替代用法。 */
    public List<Map<String, Object>> whereUsed(Long partId) {
        partService.detail(partId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (BomLine ref : bomLineMapper.selectList(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getChildPartId, partId))) {
            Bom bom = bomMapper.selectById(ref.getBomId());
            if (bom == null) {
                continue;
            }
            Part parent = partService.detail(bom.getParentPartId());
            Map<String, Object> row = baseUsageRow(bom, parent);
            row.put("usageRole", "MAIN");
            row.put("lineId", ref.getId());
            row.put("position", ref.getPosition());
            row.put("quantity", ref.getQuantity());
            result.add(row);
        }
        for (BomLineSubstitute sub : substituteMapper.selectList(new LambdaQueryWrapper<BomLineSubstitute>()
                .eq(BomLineSubstitute::getSubstitutePartId, partId)
                .orderByAsc(BomLineSubstitute::getPriority))) {
            BomLine line = bomLineMapper.selectById(sub.getBomLineId());
            if (line == null) {
                continue;
            }
            Bom bom = bomMapper.selectById(line.getBomId());
            if (bom == null) {
                continue;
            }
            Part parent = partService.detail(bom.getParentPartId());
            Part main = partService.detail(line.getChildPartId());
            Map<String, Object> row = baseUsageRow(bom, parent);
            row.put("usageRole", "SUBSTITUTE");
            row.put("mainPartId", main.getId());
            row.put("mainPartNumber", main.getPartNumber());
            row.put("mainPartName", main.getName());
            row.put("priority", sub.getPriority());
            row.put("qtyCoefficient", sub.getQtyCoefficient());
            result.add(row);
        }
        return result;
    }

    // ===== 对比 =====

    /** 两 BOM 行集合 diff：增/删/数量/位号/用量类型/属性/替代组，按行号对位排序。 */
    public BomDiffResponse compare(Long bomIdA, Long bomIdB) {
        List<BomLine> aLines = lines(bomIdA);
        List<BomLine> bLines = lines(bomIdB);
        Map<Long, BomLine> a = byChildPart(aLines);
        Map<Long, BomLine> b = byChildPart(bLines);
        Map<Long, List<BomLineSubstitute>> subsA = substitutesByLineIds(lineIds(aLines));
        Map<Long, List<BomLineSubstitute>> subsB = substitutesByLineIds(lineIds(bLines));

        List<BomDiffResponse.DiffEntry> added = new ArrayList<>();
        List<BomDiffResponse.DiffEntry> removed = new ArrayList<>();
        List<BomDiffResponse.DiffEntry> changed = new ArrayList<>();

        for (Map.Entry<Long, BomLine> e : b.entrySet()) {
            BomLine inA = a.get(e.getKey());
            if (inA == null) {
                added.add(entry(e.getValue(), null, subsB, subsB, "ADDED"));
                continue;
            }
            BomDiffResponse.DiffEntry d = entry(e.getValue(), inA, subsB, subsA, null);
            if (!d.getTypes().isEmpty()) {
                changed.add(d);
            }
        }
        for (Map.Entry<Long, BomLine> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                removed.add(entry(e.getValue(), null, subsA, subsA, "REMOVED"));
            }
        }
        Comparator<BomDiffResponse.DiffEntry> byPosition = Comparator.comparing(
                d -> d.getPosition() == null ? Integer.MAX_VALUE : d.getPosition());
        added.sort(byPosition);
        removed.sort(byPosition);
        changed.sort(byPosition);
        return new BomDiffResponse(bomIdA, bomIdB, added, removed, changed);
    }

    /** entry：newLine 为 B 侧（REMOVED 时为 A 侧），oldLine 为 A 侧（可空），structuralType 仅增/删场景传入。 */
    private BomDiffResponse.DiffEntry entry(BomLine newLine, BomLine oldLine,
                                            Map<Long, List<BomLineSubstitute>> subsNew,
                                            Map<Long, List<BomLineSubstitute>> subsOld,
                                            String structuralType) {
        Part child = partService.detail(newLine.getChildPartId());
        BomDiffResponse.DiffEntry d = new BomDiffResponse.DiffEntry();
        d.setChildPartId(child.getId());
        d.setChildPartNumber(child.getPartNumber());
        d.setChildPartName(child.getName());
        d.setPosition(newLine.getPosition());
        d.setQuantity(newLine.getQuantity());
        d.setRefDes(newLine.getRefDes());
        d.setUsageType(newLine.getUsageType());
        d.setAttrs(newLine.getAttrs());
        List<BomDiffResponse.SubstituteEntry> newSubs = substituteEntries(
                subsNew.getOrDefault(newLine.getId(), List.of()));
        if (oldLine == null) {
            d.getTypes().add(structuralType);
            if (!newSubs.isEmpty()) {
                d.setSubstitutes(newSubs);
            }
            d.setType(d.getTypes().get(0));
            return d;
        }
        if (!Objects.equals(newLine.getQuantity(), oldLine.getQuantity())) {
            d.getTypes().add("QUANTITY_CHANGED");
            d.setOldQuantity(oldLine.getQuantity());
        }
        if (!Objects.equals(normalize(newLine.getRefDes()), normalize(oldLine.getRefDes()))) {
            d.getTypes().add("REFDES_CHANGED");
            d.setOldRefDes(oldLine.getRefDes());
        }
        if (!Objects.equals(newLine.getUsageType(), oldLine.getUsageType())) {
            d.getTypes().add("USAGE_TYPE_CHANGED");
            d.setOldUsageType(oldLine.getUsageType());
        }
        if (!Objects.equals(normalize(newLine.getAttrs()), normalize(oldLine.getAttrs()))) {
            d.getTypes().add("ATTR_CHANGED");
            d.setOldAttrs(oldLine.getAttrs());
        }
        List<BomDiffResponse.SubstituteEntry> oldSubs = substituteEntries(
                subsOld.getOrDefault(oldLine.getId(), List.of()));
        if (!substituteGroupsEqual(oldSubs, newSubs)) {
            d.getTypes().add("SUBSTITUTE_CHANGED");
            d.setSubstitutes(newSubs);
            d.setOldSubstitutes(oldSubs);
        }
        d.setType(d.getTypes().get(0));
        return d;
    }

    private static String normalize(String s) {
        return s == null ? "" : s;
    }

    /** 替代组快照相等：按替代件 partId 对齐优先级与系数。 */
    private boolean substituteGroupsEqual(List<BomDiffResponse.SubstituteEntry> a,
                                          List<BomDiffResponse.SubstituteEntry> b) {
        Map<Long, List<Object>> ma = new HashMap<>();
        a.forEach(s -> ma.put(s.getSubstitutePartId(), List.of(s.getPriority(), s.getQtyCoefficient())));
        Map<Long, List<Object>> mb = new HashMap<>();
        b.forEach(s -> mb.put(s.getSubstitutePartId(), List.of(s.getPriority(), s.getQtyCoefficient())));
        return ma.equals(mb);
    }

    private List<BomDiffResponse.SubstituteEntry> substituteEntries(List<BomLineSubstitute> subs) {
        return subs.stream().map(s -> {
            BomDiffResponse.SubstituteEntry e = new BomDiffResponse.SubstituteEntry();
            Part p = partService.detail(s.getSubstitutePartId());
            e.setSubstitutePartId(p.getId());
            e.setPartNumber(p.getPartNumber());
            e.setName(p.getName());
            e.setPriority(s.getPriority());
            e.setQtyCoefficient(s.getQtyCoefficient());
            return e;
        }).collect(Collectors.toList());
    }

    // ===== 升版（结构性变更通道；替代组变更走变更中心，刀 2） =====

    /** RELEASED → 新版 DRAFT（version 次版本 +1），深拷贝行与替代组；原版本不可变。 */
    @Transactional
    public Bom revise(Long bomId, Long operatorId) {
        Bom old = requireBom(bomId);
        if (!"RELEASED".equals(old.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "仅已发布的 BOM 可升版");
        }
        Bom bom = new Bom();
        bom.setBomNumber(numberClient.next(NUMBER_RULE_KEY));
        bom.setParentPartId(old.getParentPartId());
        bom.setBomType(old.getBomType());
        bom.setVersion(nextVersion(old.getVersion()));
        bom.setLifecycleState("DRAFT");
        bom.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        bom.setCreatedBy(operatorId);
        bom.setDeleted(0);
        bomMapper.insert(bom);

        Map<Long, List<BomLineSubstitute>> subs = substitutesByLineIds(
                lineIds(lines(old.getId())));
        Map<Long, Long> lineIdMap = new LinkedHashMap<>();
        for (BomLine line : lines(old.getId())) {
            BomLine copy = new BomLine();
            copy.setBomId(bom.getId());
            copy.setChildPartId(line.getChildPartId());
            copy.setPosition(line.getPosition());
            copy.setQuantity(line.getQuantity());
            copy.setRefDes(line.getRefDes());
            copy.setUsageType(line.getUsageType());
            copy.setEffectiveFrom(line.getEffectiveFrom());
            copy.setEffectiveTo(line.getEffectiveTo());
            copy.setAttrs(line.getAttrs());
            copy.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
            bomLineMapper.insert(copy);
            lineIdMap.put(line.getId(), copy.getId());
            for (BomLineSubstitute s : subs.getOrDefault(line.getId(), List.of())) {
                BomLineSubstitute subCopy = new BomLineSubstitute();
                subCopy.setBomLineId(copy.getId());
                subCopy.setSubstitutePartId(s.getSubstitutePartId());
                subCopy.setPriority(s.getPriority());
                subCopy.setQtyCoefficient(s.getQtyCoefficient());
                subCopy.setConditionJson(s.getConditionJson());
                subCopy.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
                substituteMapper.insert(subCopy);
            }
        }
        log.info("bom revised: {} {} -> {} {}", old.getBomNumber(), old.getVersion(),
                bom.getBomNumber(), bom.getVersion());
        return bom;
    }

    /** "A/1" → "A/2"；主版本规则 M3 与物料版本策略对齐。 */
    static String nextVersion(String version) {
        int idx = version.lastIndexOf('/');
        return version.substring(0, idx + 1) + (Integer.parseInt(version.substring(idx + 1)) + 1);
    }

    // ===== 状态机 =====

    public Bom submit(Long bomId, Long operatorId) {
        return transition(bomId, "REVIEWING", operatorId);
    }

    public Bom reject(Long bomId, Long operatorId) {
        return transition(bomId, "DRAFT", operatorId);
    }

    /** 发布前强制环检测（存在循环的 BOM 不允许发布）。 */
    @Transactional
    public Bom approve(Long bomId, Long operatorId) {
        Bom bom = transition(bomId, "RELEASED", operatorId);
        expand(bomId, Integer.MAX_VALUE); // 全深度展开即环检测
        return bom;
    }

    private Bom transition(Long bomId, String target, Long operatorId) {
        Bom bom = requireBom(bomId);
        StateMachine.requireTransition(StateMachine.BOM, bom.getLifecycleState(), target);
        bom.setLifecycleState(target);
        bomMapper.updateById(bom);
        return bom;
    }

    private void requireDraftBom(Long bomId) {
        Bom bom = requireBom(bomId);
        if (!"DRAFT".equals(bom.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "仅草稿状态的 BOM 可编辑行");
        }
    }

    // ===== 私有辅助 =====

    /** 被引用物料校验：必须存在且非 DRAFT（决策 D7）。 */
    private Part requireReferenceablePart(Long partId, String role) {
        Part part = partService.detail(partId);
        if ("DRAFT".equals(part.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "草稿状态的物料不可被引用为" + role + "，请先发布物料");
        }
        return part;
    }

    /** 替代件不得为本 BOM 父件或其上层组件（装配逻辑环，设计文档 §5.5）。 */
    private void requireNotAncestor(Long bomId, Part substitute) {
        Bom bom = requireBom(bomId);
        Set<Long> ancestors = new HashSet<>(Set.of(bom.getParentPartId()));
        Set<Long> frontier = new HashSet<>(Set.of(bom.getParentPartId()));
        while (!frontier.isEmpty()) {
            List<BomLine> refs = bomLineMapper.selectList(new LambdaQueryWrapper<BomLine>()
                    .in(BomLine::getChildPartId, frontier));
            Set<Long> next = new HashSet<>();
            for (BomLine ref : refs) {
                Bom parentBom = bomMapper.selectById(ref.getBomId());
                if (parentBom != null && ancestors.add(parentBom.getParentPartId())) {
                    next.add(parentBom.getParentPartId());
                }
            }
            frontier = next;
        }
        if (ancestors.contains(substitute.getId())) {
            Part p = partService.detail(substitute.getId());
            throw new BizException(ErrorCode.BOM_CYCLE,
                    "替代件 " + p.getPartNumber() + " 是该 BOM 父件或其上层组件，构成装配逻辑环");
        }
    }

    private BomLine requireLine(Long bomId, Long lineId) {
        BomLine line = bomLineMapper.selectById(lineId);
        if (line == null || !line.getBomId().equals(bomId)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "BOM 行不存在");
        }
        return line;
    }

    private BomLineSubstitute requireSubstitute(Long lineId, Long subId) {
        BomLineSubstitute sub = substituteMapper.selectById(subId);
        if (sub == null || !sub.getBomLineId().equals(lineId)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "替代件不存在");
        }
        return sub;
    }

    private List<BomLineSubstitute> substitutesOf(Long lineId) {
        return substituteMapper.selectList(new LambdaQueryWrapper<BomLineSubstitute>()
                .eq(BomLineSubstitute::getBomLineId, lineId)
                .orderByAsc(BomLineSubstitute::getPriority).orderByAsc(BomLineSubstitute::getId));
    }

    private Map<Long, List<BomLineSubstitute>> substitutesByLineIds(Collection<Long> lineIds) {
        if (lineIds == null || lineIds.isEmpty()) {
            return Map.of();
        }
        return substituteMapper.selectList(new LambdaQueryWrapper<BomLineSubstitute>()
                .in(BomLineSubstitute::getBomLineId, lineIds)
                .orderByAsc(BomLineSubstitute::getPriority).orderByAsc(BomLineSubstitute::getId))
                .stream()
                .collect(Collectors.groupingBy(BomLineSubstitute::getBomLineId));
    }

    private List<BomLineResponse.SubstituteView> substituteViews(List<BomLineSubstitute> subs) {
        return subs.stream().map(s -> {
            Part p = partService.detail(s.getSubstitutePartId());
            BomLineResponse.SubstituteView v = new BomLineResponse.SubstituteView();
            v.setId(s.getId());
            v.setSubstitutePartId(p.getId());
            v.setPartNumber(p.getPartNumber());
            v.setName(p.getName());
            v.setPriority(s.getPriority());
            v.setQtyCoefficient(s.getQtyCoefficient());
            return v;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> baseUsageRow(Bom bom, Part parent) {
        Map<String, Object> row = new HashMap<>();
        row.put("bomId", bom.getId());
        row.put("bomNumber", bom.getBomNumber());
        row.put("bomState", bom.getLifecycleState());
        row.put("parentPartId", parent.getId());
        row.put("parentPartNumber", parent.getPartNumber());
        row.put("parentPartName", parent.getName());
        return row;
    }

    private Map<Long, BomLine> byChildPart(List<BomLine> ls) {
        return ls.stream().collect(Collectors.toMap(BomLine::getChildPartId, l -> l, (x, y) -> x,
                LinkedHashMap::new));
    }

    private List<Long> lineIds(List<BomLine> ls) {
        return ls.stream().map(BomLine::getId).collect(Collectors.toList());
    }

    private Integer nextPosition(List<BomLine> existing) {
        return existing.stream()
                .mapToInt(l -> l.getPosition() == null ? 0 : l.getPosition())
                .max().orElse(0) + 1;
    }

    private int nextPriority(List<BomLineSubstitute> group) {
        return group.stream().mapToInt(s -> s.getPriority() == null ? 0 : s.getPriority())
                .max().orElse(0) + 1;
    }

    /** 行号紧缩：删除行后同 BOM 内按现顺序重排 1..n。 */
    private void resequence(Long bomId) {
        List<BomLine> remaining = lines(bomId);
        int pos = 1;
        for (BomLine line : remaining) {
            if (line.getPosition() == null || line.getPosition() != pos) {
                line.setPosition(pos);
                bomLineMapper.updateById(line);
            }
            pos++;
        }
    }

    /** 展开树节点。 */
    public record BomNode(Long partId, String partNumber, String name,
                          BigDecimal quantity, List<SubstituteNode> substitutes,
                          List<BomNode> children) {
    }

    /** 展开树替代组标注。 */
    public record SubstituteNode(Long partId, String partNumber, String name,
                                 Integer priority, BigDecimal qtyCoefficient) {
    }
}
