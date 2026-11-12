package com.openforge.material;

import com.openforge.common.api.BizException;
import com.openforge.material.client.NumberClient;
import com.openforge.common.event.EventPublisher;
import com.openforge.material.dto.CreatePartRequest;
import com.openforge.material.entity.Part;
import com.openforge.material.service.PartService;
import com.openforge.material.service.RecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 物料回收站集成（v1.23 设计 §3，H2）：软删→回收站可见→恢复回业务侧；
 * 重复恢复/不存在按 404 语义；租户过滤由 TenantLineInnerInterceptor 承载。
 */
@SpringBootTest
class RecycleIntegrationTest {

    @Autowired
    private PartService partService;

    @Autowired
    private RecycleService recycleService;

    @MockBean
    private NumberClient numberClient;

    @MockBean
    private com.openforge.material.client.MaterialNotifyClient notifyClient;


    @MockBean
    private EventPublisher eventPublisher;

    @Autowired
    private com.openforge.material.service.BomService bomService;

    @Autowired
    private com.openforge.material.mapper.BomLineMapper lineMapper;

    private static final java.util.concurrent.atomic.AtomicLong bomSeq = new java.util.concurrent.atomic.AtomicLong(900);

    @org.junit.jupiter.api.BeforeEach
    void stubBomNumbers() {
        org.mockito.Mockito.when(numberClient.next("bom"))
                .thenAnswer(i -> "B" + String.format("%010d", bomSeq.incrementAndGet()));
    }

    private static final AtomicLong partSeq = new AtomicLong(900);
    private static Long catId;

    @Autowired
    private com.openforge.material.service.CategoryService categoryService;

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("part")).thenAnswer(i -> "P" + String.format("%010d", partSeq.incrementAndGet()));
    }

    private Part part(String name) {
        if (catId == null) {
            catId = categoryService.create("RC_CAT", "回收站测试分类", null, 0).getId();
        }
        CreatePartRequest r = new CreatePartRequest();
        r.setName(name);
        r.setType("MADE");
        r.setCategoryId(catId);
        return partService.create(r);
    }

    @Test
    @DisplayName("删除→回收站可见→恢复→业务侧可见且回收站移除")
    void deleteRestoreRoundtrip() {
        Part p = part("回收站往返物料");
        partService.deleteDraft(p.getId());

        assertThat(recycleService.trashedParts()).anyMatch(t -> t.getId().equals(p.getId()));
        assertThat(partService.page(1, 100, null, "回收站往返物料", null, null).list()).isEmpty();

        Part restored = recycleService.restore(p.getId());
        assertThat(restored.getId()).isEqualTo(p.getId());
        assertThat(partService.page(1, 100, null, "回收站往返物料", null, null).list())
                .anyMatch(x -> x.getId().equals(p.getId()));
        assertThat(recycleService.trashedParts()).noneMatch(t -> t.getId().equals(p.getId()));
    }

    @Test
    @DisplayName("未删除行与重复恢复按不存在应答")
    void restoreGuards() {
        Part p = part("未删除物料");
        assertThatThrownBy(() -> recycleService.restore(p.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该物料");
        partService.deleteDraft(p.getId());
        assertThat(recycleService.restore(p.getId())).isNotNull();
        // 已恢复（deleted=0），再次恢复同样按不存在
        assertThatThrownBy(() -> recycleService.restore(p.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该物料");
    }

    @Test
    @DisplayName("BOM 删除→回收站→恢复往返；已发布 BOM 拒绝删除")
    void bomDeleteRestoreRoundtrip() {
        Part parent = releasedPart("BOM回收主件");
        com.openforge.material.entity.Bom bom = bomService.create(parent.getId(), 1L);
        bomService.deleteDraft(bom.getId());

        assertThat(recycleService.trashedBoms()).anyMatch(b -> b.getId().equals(bom.getId()));
        com.openforge.material.entity.Bom restored = recycleService.restoreBom(bom.getId());
        assertThat(restored.getId()).isEqualTo(bom.getId());
        assertThat(recycleService.trashedBoms()).noneMatch(b -> b.getId().equals(bom.getId()));

        // 已发布 BOM 不可删除（行子件 RELEASED 且 ≠ 父件，避免环）
        Part child = releasedPart("BOM发布子件");
        com.openforge.material.entity.Bom released = bomService.create(parent.getId(), 1L);
        bomService.addLine(released.getId(), lineOf(child.getId()));
        bomService.submit(released.getId(), 1L);
        bomService.approve(released.getId(), 1L);
        assertThatThrownBy(() -> bomService.deleteDraft(released.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("仅草稿 BOM 可删除");
    }

    @Test
    @DisplayName("彻底删除：物料被 BOM 行引用时拒绝；purge BOM 清行后物料可彻底删除")
    void purgeWithReferenceGuard() {
        Part parent = part("purge父件");
        Part child = part("purge子件");
        com.openforge.material.entity.Bom bom = bomService.create(parent.getId(), 1L);
        // 直插 bom_line 模拟脏引用（正常流要求子件 RELEASED，故 deleteDraft 场景只能经此构造）
        com.openforge.material.entity.BomLine dirty = new com.openforge.material.entity.BomLine();
        dirty.setBomId(bom.getId());
        dirty.setChildPartId(child.getId());
        dirty.setPosition(1);
        dirty.setQuantity(new java.math.BigDecimal("2"));
        lineMapper.insert(dirty);

        partService.deleteDraft(child.getId());
        assertThatThrownBy(() -> recycleService.purgePart(child.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("BOM 行/替代组引用");

        bomService.deleteDraft(bom.getId());
        recycleService.purgeBom(bom.getId());
        assertThat(recycleService.trashedBoms()).noneMatch(b -> b.getId().equals(bom.getId()));
        recycleService.purgePart(child.getId());
        assertThat(recycleService.trashedParts()).noneMatch(t -> t.getId().equals(child.getId()));
        assertThatThrownBy(() -> recycleService.restore(child.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该物料");
    }

    /** addLine 要求被引用物料为 RELEASED（requireReferenceablePart）。 */
    private Part releasedPart(String name) {
        Part p = part(name);
        partService.submit(p.getId(), 1L);
        return partService.approve(p.getId(), 1L);
    }

    private com.openforge.material.dto.BomLineRequest lineOf(Long childPartId) {
        com.openforge.material.dto.BomLineRequest l = new com.openforge.material.dto.BomLineRequest();
        l.setChildPartId(childPartId);
        l.setQuantity(new java.math.BigDecimal("2"));
        return l;
    }
}
