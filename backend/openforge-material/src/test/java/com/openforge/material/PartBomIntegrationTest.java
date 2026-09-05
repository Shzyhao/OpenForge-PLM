package com.openforge.material;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.client.NumberClient;
import com.openforge.material.dto.BomDiffResponse;
import com.openforge.material.dto.BomLineRequest;
import com.openforge.material.dto.BomLineResponse;
import com.openforge.material.dto.CreatePartRequest;
import com.openforge.material.dto.SubstituteRequest;
import com.openforge.material.dto.SubstituteUpdateRequest;
import com.openforge.material.entity.Bom;
import com.openforge.material.entity.BomLine;
import com.openforge.material.entity.Part;
import com.openforge.material.entity.PartVersion;
import com.openforge.material.mapper.BomLineMapper;
import com.openforge.material.mapper.PartVersionMapper;
import com.openforge.material.service.BomService;
import com.openforge.material.service.CategoryService;
import com.openforge.material.service.PartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * M2-2/M2-3 集成：属性模板校验、物料状态机与发布快照、BOM 展开/环检测/反查/对比。
 * 刀1 扩展：替代组校验矩阵、行号维护、D7 引用收紧、升版与跨版本 diff。
 */
@SpringBootTest
class PartBomIntegrationTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private PartService partService;
    @Autowired
    private BomService bomService;
    @Autowired
    private PartVersionMapper partVersionMapper;
    @Autowired
    private BomLineMapper bomLineMapper;

    @MockBean
    private NumberClient numberClient;

    // JUnit 每个测试方法新建实例，而 H2 数据跨方法共享——序列与分类缓存必须静态
    private static final AtomicLong partSeq = new AtomicLong(100);
    private static final AtomicLong bomSeq = new AtomicLong(100);
    private static Long catId;

    @BeforeEach
    void stubNumbers() {
        // @MockBean 每个测试方法后重置，统一在此 stub：part/bom 规则各自递增
        when(numberClient.next("part")).thenAnswer(i -> "P" + String.format("%010d", partSeq.incrementAndGet()));
        when(numberClient.next("bom")).thenAnswer(i -> "B" + String.format("%010d", bomSeq.incrementAndGet()));
    }

    private Long category() {
        if (catId == null) {
            catId = categoryService.create("PB_CAT", "状态机测试分类", null, 0).getId();
        }
        return catId;
    }

    private Part part(String name, String attrs) {
        CreatePartRequest r = new CreatePartRequest();
        r.setName(name);
        r.setType("MADE");
        r.setCategoryId(category());
        r.setAttrs(attrs);
        return partService.create(r);
    }

    /** 决策 D7：被 BOM 引用的物料必须先发布。 */
    private Part releasedPart(String name) {
        Part p = part(name, null);
        partService.submit(p.getId(), 1L);
        return partService.approve(p.getId(), 1L);
    }

    private BomLineRequest line(Long childPartId, String quantity) {
        BomLineRequest l = new BomLineRequest();
        l.setChildPartId(childPartId);
        l.setQuantity(new BigDecimal(quantity));
        return l;
    }

    private SubstituteRequest sub(Long substitutePartId, Integer priority, String coefficient) {
        SubstituteRequest s = new SubstituteRequest();
        s.setSubstitutePartId(substitutePartId);
        s.setPriority(priority);
        s.setQtyCoefficient(coefficient == null ? null : new BigDecimal(coefficient));
        return s;
    }

    private BizExceptionCode codeOf(Throwable t) {
        return new BizExceptionCode(((BizException) t).getErrorCode());
    }

    private record BizExceptionCode(ErrorCode code) {
    }

    @Test
    @DisplayName("属性模板：设置后建料缺必填被拒，补齐后通过")
    void attrTemplateEnforcedOnCreate() {
        Long cat = categoryService.create("TPL_CAT", "模板分类", null, 0).getId();
        categoryService.setAttrTemplate(cat, "[{\"key\":\"material\",\"label\":\"材质\",\"type\":\"string\",\"required\":true}]");

        CreatePartRequest bad = new CreatePartRequest();
        bad.setName("无属性件");
        bad.setType("MADE");
        bad.setCategoryId(cat);
        assertThatThrownBy(() -> partService.create(bad))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ATTR_VALIDATION_FAILED));

        bad.setAttrs("{\"material\":\"铝合金\"}");
        assertThat(partService.create(bad)).isNotNull();
    }

    @Test
    @DisplayName("物料状态机：DRAFT→REVIEWING→RELEASED 生成版本快照；非法流转拒绝")
    void partStateMachineAndSnapshot() {
        Part part = part("状态机测试件", null);

        // 非法: DRAFT 直接发布
        assertThatThrownBy(() -> partService.approve(part.getId(), 1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        partService.submit(part.getId(), 1L);
        Part released = partService.approve(part.getId(), 1L);
        assertThat(released.getLifecycleState()).isEqualTo("RELEASED");

        // 发布快照
        List<PartVersion> versions = partVersionMapper.selectList(null);
        assertThat(versions).anyMatch(v -> v.getPartId().equals(part.getId())
                && v.getVersion().equals("A/1") && v.getSnapshot().contains("状态机测试件"));

        // 已发布物料不可编辑
        assertThatThrownBy(() -> partService.deleteDraft(part.getId()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("BOM 全链路：三层展开正确、环检测拒绝、反查命中、对比差异")
    void bomFullChain() {
        Part a = releasedPart("组件A");
        Part b = releasedPart("组件B");
        Part c = releasedPart("零件C");

        Bom bomA = bomService.create(a.getId(), 1L);
        bomService.addLine(bomA.getId(), line(b.getId(), "2"));
        Bom bomB = bomService.create(b.getId(), 1L);
        bomService.addLine(bomB.getId(), line(c.getId(), "4"));

        // 三层展开
        BomService.BomNode tree = bomService.expand(bomA.getId(), 5);
        assertThat(tree.partNumber()).isEqualTo(a.getPartNumber());
        assertThat(tree.children()).hasSize(1);
        assertThat(tree.children().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(tree.children().get(0).children()).hasSize(1);
        assertThat(tree.children().get(0).children().get(0).partNumber()).isEqualTo(c.getPartNumber());

        // 环检测: B 的 BOM 引用 A → 展开 A 应报循环
        bomService.addLine(bomB.getId(), line(a.getId(), "1"));
        assertThatThrownBy(() -> bomService.expand(bomA.getId(), 5))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BOM_CYCLE));

        // 反查 C
        List<java.util.Map<String, Object>> where = bomService.whereUsed(c.getId());
        assertThat(where).hasSize(1);
        assertThat(where.get(0).get("parentPartNumber")).isEqualTo(b.getPartNumber());
        assertThat(where.get(0).get("usageRole")).isEqualTo("MAIN");

        // 对比: 同一 BOM 不同版本——复制一个新 BOM 改数量
        Bom bomA2 = bomService.create(a.getId(), 1L);
        bomService.addLine(bomA2.getId(), line(b.getId(), "3"));
        bomService.addLine(bomA2.getId(), line(c.getId(), "1"));
        BomDiffResponse diff = bomService.compare(bomA.getId(), bomA2.getId());
        assertThat(diff.added()).hasSize(1);
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.changed().get(0).getQuantity()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("决策 D7：草稿物料不可被 BOM 行/替代件引用，发布后放行")
    void draftPartReferenceRejected() {
        Part parent = releasedPart("引用父件");
        Part draftChild = part("未发布子件", null);
        Part releasedChild = releasedPart("已发布子件");

        Bom bom = bomService.create(parent.getId(), 1L);
        assertThatThrownBy(() -> bomService.addLine(bom.getId(), line(draftChild.getId(), "1")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ARGUMENT));

        BomLine l = bomService.addLine(bom.getId(), line(releasedChild.getId(), "1"));
        assertThatThrownBy(() -> bomService.addSubstitute(bom.getId(), l.getId(), sub(draftChild.getId(), 1, "1")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("替代组：增删改、自替代/重复/系数非法/祖先环校验、展开标注、替代反查")
    void substituteGroupLifecycle() {
        Part a = releasedPart("替代主组件");
        Part b = releasedPart("替代子件");
        Part c = releasedPart("替代件C");
        Part d = releasedPart("替代件D");
        Part e = releasedPart("上层组件E");

        Bom bomA = bomService.create(a.getId(), 1L);
        BomLine lineB = bomService.addLine(bomA.getId(), line(b.getId(), "2"));
        bomService.addSubstitute(bomA.getId(), lineB.getId(), sub(c.getId(), 1, "1"));
        bomService.addSubstitute(bomA.getId(), lineB.getId(), sub(d.getId(), null, "2.5")); // 优先级缺省追加组尾=2

        // 校验矩阵：自替代 / 同行重复 / 系数非法 / 上层组件环
        assertThatThrownBy(() -> bomService.addSubstitute(bomA.getId(), lineB.getId(), sub(b.getId(), 1, "1")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> bomService.addSubstitute(bomA.getId(), lineB.getId(), sub(c.getId(), 1, "1")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> bomService.addSubstitute(bomA.getId(), lineB.getId(), sub(e.getId(), 1, "0")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT));

        // E 的 BOM 引用 A → A 的上层组件含 E；此时把 E 加为替代件应判装配逻辑环
        Bom bomE = bomService.create(e.getId(), 1L);
        bomService.addLine(bomE.getId(), line(a.getId(), "1"));
        assertThatThrownBy(() -> bomService.addSubstitute(bomA.getId(), lineB.getId(), sub(e.getId(), 1, "1")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex).code()).isEqualTo(ErrorCode.BOM_CYCLE));

        // 调整优先级与系数
        List<BomLineResponse.SubstituteView> views = bomService.substitutes(bomA.getId(), lineB.getId());
        assertThat(views).hasSize(2);
        assertThat(views.get(0).getPartNumber()).isEqualTo(c.getPartNumber());
        assertThat(views.get(1).getQtyCoefficient()).isEqualByComparingTo("2.5");
        SubstituteUpdateRequest upd = new SubstituteUpdateRequest();
        upd.setPriority(1);
        upd.setQtyCoefficient(new BigDecimal("3"));
        bomService.updateSubstitute(bomA.getId(), lineB.getId(), views.get(1).getId(), upd);

        // 展开标注替代组
        BomService.BomNode tree = bomService.expand(bomA.getId(), 3);
        assertThat(tree.children().get(0).substitutes()).hasSize(2);
        assertThat(tree.children().get(0).substitutes().get(0).partNumber()).isEqualTo(c.getPartNumber());

        // 替代件反查：usageRole=SUBSTITUTE 且定位主件行
        List<java.util.Map<String, Object>> where = bomService.whereUsed(c.getId());
        assertThat(where).hasSize(1);
        assertThat(where.get(0).get("usageRole")).isEqualTo("SUBSTITUTE");
        assertThat(where.get(0).get("mainPartNumber")).isEqualTo(b.getPartNumber());
        assertThat(where.get(0).get("parentPartNumber")).isEqualTo(a.getPartNumber());

        // 移除替代件
        bomService.removeSubstitute(bomA.getId(), lineB.getId(), views.get(0).getId());
        assertThat(bomService.substitutes(bomA.getId(), lineB.getId())).hasSize(1);
    }

    @Test
    @DisplayName("行号维护：顺序追加、删除后紧缩 1..n")
    void linePositionMaintained() {
        Part parent = releasedPart("行号父件");
        Part p1 = releasedPart("行号件1");
        Part p2 = releasedPart("行号件2");
        Part p3 = releasedPart("行号件3");

        Bom bom = bomService.create(parent.getId(), 1L);
        BomLine l1 = bomService.addLine(bom.getId(), line(p1.getId(), "1"));
        BomLine l2 = bomService.addLine(bom.getId(), line(p2.getId(), "1"));
        bomService.addLine(bom.getId(), line(p3.getId(), "1"));
        assertThat(bomService.lines(bom.getId())).extracting(BomLine::getPosition)
                .containsExactly(1, 2, 3);

        bomService.removeLine(bom.getId(), l2.getId());
        List<BomLine> rest = bomService.lines(bom.getId());
        assertThat(rest).extracting(BomLine::getPosition).containsExactly(1, 2);
        assertThat(rest).extracting(BomLine::getId)
                .containsExactlyInAnyOrder(l1.getId(), l1.getId() + 2)
                .doesNotContain(l2.getId());
    }

    @Test
    @DisplayName("升版：RELEASED→A/2 新草稿深拷贝行与替代组；旧版不可变；跨版本 diff 含替代组变更")
    void reviseAndCrossVersionDiff() {
        Part a = releasedPart("升版父件");
        Part b = releasedPart("升版子件");
        Part c = releasedPart("升版替代C");
        Part d = releasedPart("升版替代D");

        Bom v1 = bomService.create(a.getId(), 1L);
        BomLine lineB = bomService.addLine(v1.getId(), line(b.getId(), "2"));
        lineB.setRefDes("R1,R2");
        bomLineMapper.updateById(lineB);
        bomService.addSubstitute(v1.getId(), lineB.getId(), sub(c.getId(), 1, "1"));
        bomService.addSubstitute(v1.getId(), lineB.getId(), sub(d.getId(), 2, "1"));
        bomService.submit(v1.getId(), 1L);
        bomService.approve(v1.getId(), 1L);

        // 未发布不可升版
        Bom draftBom = bomService.create(releasedPart("升版旁路件").getId(), 1L);
        assertThatThrownBy(() -> bomService.revise(draftBom.getId(), 1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT));

        // 升版：A/1 → A/2，深拷贝行 + 替代组
        Bom v2 = bomService.revise(v1.getId(), 1L);
        assertThat(v2.getVersion()).isEqualTo("A/2");
        assertThat(v2.getLifecycleState()).isEqualTo("DRAFT");
        assertThat(v2.getId()).isNotEqualTo(v1.getId());
        List<BomLine> v2Lines = bomService.lines(v2.getId());
        assertThat(v2Lines).hasSize(1);
        assertThat(v2Lines.get(0).getChildPartId()).isEqualTo(b.getId());
        assertThat(v2Lines.get(0).getRefDes()).isEqualTo("R1,R2");
        assertThat(v2Lines.get(0).getPosition()).isEqualTo(1);
        assertThat(bomService.substitutes(v2.getId(), v2Lines.get(0).getId())).hasSize(2);

        // 旧版不可变
        assertThat(bomService.requireBom(v1.getId()).getLifecycleState()).isEqualTo("RELEASED");
        assertThat(bomService.lines(v1.getId())).hasSize(1);

        // 新版调整：删替代 D、改数量与位号 → 跨版本 diff 命中三类变更
        BomLine v2LineB = v2Lines.get(0);
        BomLineResponse.SubstituteView subD = bomService.substitutes(v2.getId(), v2LineB.getId()).stream()
                .filter(s -> s.getSubstitutePartId().equals(d.getId()))
                .findFirst().orElseThrow();
        bomService.removeSubstitute(v2.getId(), v2LineB.getId(), subD.getId());
        v2LineB.setQuantity(new BigDecimal("3"));
        v2LineB.setRefDes("R1,R2,R3");
        bomLineMapper.updateById(v2LineB);

        BomDiffResponse diff = bomService.compare(v1.getId(), v2.getId());
        assertThat(diff.changed()).hasSize(1);
        BomDiffResponse.DiffEntry entry = diff.changed().get(0);
        assertThat(entry.getChildPartId()).isEqualTo(b.getId());
        assertThat(entry.getTypes()).contains("QUANTITY_CHANGED", "REFDES_CHANGED", "SUBSTITUTE_CHANGED");
        assertThat(entry.getSubstitutes()).hasSize(1);
        assertThat(entry.getSubstitutes().get(0).getPartNumber()).isEqualTo(c.getPartNumber());
        assertThat(entry.getOldSubstitutes()).hasSize(2);

        // 完全相同的两版本（v1 与其未改动升版副本 v3）：compare 不崩、三清单皆空（真实链路曾 500 的路径）
        Bom v3 = bomService.revise(v1.getId(), 1L);
        BomDiffResponse identical = bomService.compare(v1.getId(), v3.getId());
        assertThat(identical.added()).isEmpty();
        assertThat(identical.removed()).isEmpty();
        assertThat(identical.changed()).isEmpty();
    }
}
