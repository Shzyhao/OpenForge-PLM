package com.openforge.material;

import com.openforge.common.api.BizException;
import com.openforge.material.client.NumberClient;
import com.openforge.material.dto.CreatePartRequest;
import com.openforge.material.dto.PageResponse;
import com.openforge.material.dto.UpdatePartRequest;
import com.openforge.material.entity.Part;
import com.openforge.material.entity.PartCategory;
import com.openforge.material.mapper.PartMapper;
import com.openforge.material.service.CategoryService;
import com.openforge.material.service.PartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 物料域 H2 集成验证：分类树 → 自动取号建料 → 分页过滤 → 草态保护。
 * NumberClient 以 MockBean 替换（真实取号链路由 auth 侧 InternalControllerTest 覆盖）。
 */
@SpringBootTest
class MaterialIntegrationTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private PartService partService;
    @Autowired
    private PartMapper partMapper;

    @MockBean
    private NumberClient numberClient;

    private Long createCategory(String code, String name, Long parentId) {
        return categoryService.create(code, name, parentId, 0).getId();
    }

    private CreatePartRequest partRequest(String name, Long categoryId) {
        CreatePartRequest r = new CreatePartRequest();
        r.setName(name);
        r.setType("MADE");
        r.setCategoryId(categoryId);
        r.setUnit("件");
        r.setAttrs("{\"材质\":\"45#钢\",\"外径\":\"120\"}");
        return r;
    }

    @Test
    @DisplayName("分类树 + 自动取号 + 分页含子级过滤 + 草态保护 全链路")
    void partLifecycleDraftFlow() {
        when(numberClient.next(anyString())).thenReturn("P2026082300999");

        // 分类树：机械(根) → 标准件(子)
        Long root = createCategory("MECH", "机械件", null);
        Long child = createCategory("STD", "标准件", root);

        // 建物料（挂子分类）
        Part part = partService.create(partRequest("法兰盘", child));
        assertThat(part.getPartNumber()).isEqualTo("P2026082300999");
        assertThat(part.getLifecycleState()).isEqualTo("DRAFT");
        assertThat(part.getVersion()).isEqualTo("A/1");

        // 按父分类过滤应命中子分类下的物料（物化路径前缀）
        PageResponse<Part> byRoot = partService.page(1, 10, root, null, null, null);
        assertThat(byRoot.total()).isEqualTo(1);
        PageResponse<Part> byName = partService.page(1, 10, null, "法兰", null, null);
        assertThat(byName.total()).isEqualTo(1);

        // 草稿可编辑
        UpdatePartRequest upd = new UpdatePartRequest();
        upd.setName("法兰盘 45#");
        partService.updateDraft(part.getId(), upd);
        assertThat(partService.detail(part.getId()).getName()).isEqualTo("法兰盘 45#");

        // 模拟发布后（非草稿）不可编辑/删除
        part.setLifecycleState("RELEASED");
        partMapper.updateById(part);
        assertThatThrownBy(() -> partService.updateDraft(part.getId(), new UpdatePartRequest()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> partService.deleteDraft(part.getId()))
                .isInstanceOf(BizException.class);

        // 清理
        partMapper.deleteById(part.getId());
    }

    @Test
    @DisplayName("分类不存在时建物料失败")
    void createWithUnknownCategoryShouldFail() {
        CreatePartRequest r = partRequest("幽灵件", 999999L);
        assertThatThrownBy(() -> partService.create(r))
                .isInstanceOf(BizException.class);
    }
}
