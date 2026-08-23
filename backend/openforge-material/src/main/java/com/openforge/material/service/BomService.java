package com.openforge.material.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.client.NumberClient;
import com.openforge.material.dto.BomDiffResponse;
import com.openforge.material.dto.BomLineRequest;
import com.openforge.material.entity.Bom;
import com.openforge.material.entity.BomLine;
import com.openforge.material.entity.Part;
import com.openforge.material.mapper.BomLineMapper;
import com.openforge.material.mapper.BomMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** BOM 管理（开发文档 3.1.2）：行维护、多层展开（环检测）、反查、版本对比、状态机。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomService {

    static final String NUMBER_RULE_KEY = "bom";

    private final BomMapper bomMapper;
    private final BomLineMapper bomLineMapper;
    private final PartService partService;
    private final NumberClient numberClient;

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
        bom.setTenantId(0L);
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
        partService.detail(request.getChildPartId()); // 子件必须存在
        Long dup = bomLineMapper.selectCount(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getBomId, bomId).eq(BomLine::getChildPartId, request.getChildPartId()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "该子件已存在于 BOM 中");
        }
        BomLine line = new BomLine();
        line.setBomId(bomId);
        line.setChildPartId(request.getChildPartId());
        line.setQuantity(request.getQuantity());
        line.setRefDes(request.getRefDes());
        line.setUsageType(request.getUsageType() == null ? "NORMAL" : request.getUsageType());
        line.setEffectiveFrom(request.getEffectiveFrom());
        line.setEffectiveTo(request.getEffectiveTo());
        line.setTenantId(0L);
        bomLineMapper.insert(line);
        return line;
    }

    public List<BomLine> lines(Long bomId) {
        requireBom(bomId);
        return bomLineMapper.selectList(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getBomId, bomId).orderByAsc(BomLine::getId));
    }

    public void removeLine(Long bomId, Long lineId) {
        requireDraftBom(bomId);
        bomLineMapper.delete(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getBomId, bomId).eq(BomLine::getId, lineId));
    }

    // ===== 展开（含环检测） / 反查 =====

    /** 多层展开为树；展开过程中检测循环引用。level 为最大层数（1=单层）。 */
    public BomNode expand(Long bomId, int level) {
        Bom bom = requireBom(bomId);
        BomNode root = expandNode(bom, level, new HashSet<>(Set.of(bom.getParentPartId())));
        return root;
    }

    private BomNode expandNode(Bom bom, int level, Set<Long> pathAncestors) {
        Part parent = partService.detail(bom.getParentPartId());
        BomNode node = new BomNode(parent.getId(), parent.getPartNumber(), parent.getName(),
                BigDecimal.ONE, new ArrayList<>());
        if (level <= 0) {
            return node;
        }
        List<BomLine> lines = bomLineMapper.selectList(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getBomId, bom.getId()));
        for (BomLine line : lines) {
            if (pathAncestors.contains(line.getChildPartId())) {
                Part cyclePart = partService.detail(line.getChildPartId());
                throw new BizException(ErrorCode.BOM_CYCLE,
                        "循环引用: " + cyclePart.getPartNumber() + " 出现在自身祖先路径上");
            }
            Part child = partService.detail(line.getChildPartId());
            BomNode childNode = new BomNode(child.getId(), child.getPartNumber(), child.getName(),
                    line.getQuantity(), new ArrayList<>());
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

    /** 反查（Where-Used）：使用指定物料的 BOM 及其父件。 */
    public List<Map<String, Object>> whereUsed(Long partId) {
        partService.detail(partId);
        List<BomLine> refs = bomLineMapper.selectList(new LambdaQueryWrapper<BomLine>()
                .eq(BomLine::getChildPartId, partId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (BomLine ref : refs) {
            Bom bom = bomMapper.selectById(ref.getBomId());
            if (bom == null) {
                continue;
            }
            Part parent = partService.detail(bom.getParentPartId());
            Map<String, Object> row = new HashMap<>();
            row.put("bomId", bom.getId());
            row.put("bomNumber", bom.getBomNumber());
            row.put("bomState", bom.getLifecycleState());
            row.put("parentPartId", parent.getId());
            row.put("parentPartNumber", parent.getPartNumber());
            row.put("parentPartName", parent.getName());
            row.put("quantity", ref.getQuantity());
            result.add(row);
        }
        return result;
    }

    // ===== 对比 =====

    /** 两 BOM 行集合 diff：新增 / 删除 / 数量变更。 */
    public BomDiffResponse compare(Long bomIdA, Long bomIdB) {
        Map<Long, BomLine> a = lines(bomIdA).stream()
                .collect(Collectors.toMap(BomLine::getChildPartId, l -> l));
        Map<Long, BomLine> b = lines(bomIdB).stream()
                .collect(Collectors.toMap(BomLine::getChildPartId, l -> l));

        List<BomDiffResponse.DiffEntry> added = new ArrayList<>();
        List<BomDiffResponse.DiffEntry> removed = new ArrayList<>();
        List<BomDiffResponse.DiffEntry> changed = new ArrayList<>();

        for (Map.Entry<Long, BomLine> e : b.entrySet()) {
            BomLine inA = a.get(e.getKey());
            if (inA == null) {
                added.add(entry(e.getValue(), "ADDED"));
            } else if (inA.getQuantity().compareTo(e.getValue().getQuantity()) != 0) {
                BomDiffResponse.DiffEntry d = entry(e.getValue(), "QUANTITY_CHANGED");
                d.setOldQuantity(inA.getQuantity());
                changed.add(d);
            }
        }
        for (Map.Entry<Long, BomLine> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                removed.add(entry(e.getValue(), "REMOVED"));
            }
        }
        return new BomDiffResponse(bomIdA, bomIdB, added, removed, changed);
    }

    private BomDiffResponse.DiffEntry entry(BomLine line, String type) {
        Part child = partService.detail(line.getChildPartId());
        BomDiffResponse.DiffEntry d = new BomDiffResponse.DiffEntry();
        d.setType(type);
        d.setChildPartId(line.getChildPartId());
        d.setChildPartNumber(child.getPartNumber());
        d.setChildPartName(child.getName());
        d.setQuantity(line.getQuantity());
        return d;
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

    /** 展开树节点。 */
    public record BomNode(Long partId, String partNumber, String name,
                          BigDecimal quantity, List<BomNode> children) {
    }
}
