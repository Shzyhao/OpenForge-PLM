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
    private EventPublisher eventPublisher;

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
}
