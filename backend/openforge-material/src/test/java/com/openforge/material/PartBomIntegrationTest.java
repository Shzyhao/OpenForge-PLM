package com.openforge.material;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.client.NumberClient;
import com.openforge.material.dto.BomDiffResponse;
import com.openforge.material.dto.BomLineRequest;
import com.openforge.material.dto.CreatePartRequest;
import com.openforge.material.entity.Bom;
import com.openforge.material.entity.Part;
import com.openforge.material.entity.PartVersion;
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

    private BomLineRequest line(Long childPartId, String quantity) {
        BomLineRequest l = new BomLineRequest();
        l.setChildPartId(childPartId);
        l.setQuantity(new BigDecimal(quantity));
        return l;
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
        Part a = part("组件A", null);
        Part b = part("组件B", null);
        Part c = part("零件C", null);

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

        // 对比: 同一 BOM 不同版本——复制一个新 BOM 改数量
        Bom bomA2 = bomService.create(a.getId(), 1L);
        bomService.addLine(bomA2.getId(), line(b.getId(), "3"));
        bomService.addLine(bomA2.getId(), line(c.getId(), "1"));
        BomDiffResponse diff = bomService.compare(bomA.getId(), bomA2.getId());
        assertThat(diff.added()).hasSize(1);
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.changed().get(0).getQuantity()).isEqualByComparingTo("3");
    }
}
