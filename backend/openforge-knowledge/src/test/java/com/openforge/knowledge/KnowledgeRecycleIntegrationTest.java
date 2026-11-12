package com.openforge.knowledge;

import com.openforge.common.api.BizException;
import com.openforge.knowledge.entity.KnowledgeItem;
import com.openforge.knowledge.service.EmbeddingClient;
import com.openforge.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识回收站集成（v1.25 设计 §4，H2）：软删（向量保留）→恢复；purge 连带反馈 GC + 行物理消失。
 * EmbeddingClient 为 MockBean；向量面走 InMemoryVectorStore（remove 断言简化为行级验证）。
 */
@SpringBootTest
class KnowledgeRecycleIntegrationTest {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingClient embeddingClient;  // 真实离线词袋（MockBean 会使 embed 返回 null → vectorStore.add NPE）

    @Test
    @DisplayName("软删→回收站→恢复往返；purge 后行物理消失且反馈 GC，restore 404")
    void deleteRestorePurge() {
        KnowledgeItem item = knowledgeService.create("回收站条目", "内容", "tag", "MANUAL", "recycle-test-1", 1L);

        knowledgeService.deleteItem(item.getId());
        assertThat(knowledgeService.trashed()).anyMatch(t -> t.getId().equals(item.getId()));

        KnowledgeItem restored = knowledgeService.restoreItem(item.getId());
        assertThat(restored.getId()).isEqualTo(item.getId());
        assertThat(knowledgeService.trashed()).noneMatch(t -> t.getId().equals(item.getId()));

        // purge 面：再造一条反馈 → purge 后 item/feedback 行均物理消失
        knowledgeService.deleteItem(item.getId());
        jdbc.update("INSERT INTO knowledge_feedback (query_text, item_id, user_id, action) VALUES ('测试查询', ?, 1, 'CLICK')", item.getId());
        knowledgeService.purgeItem(item.getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_item WHERE id = ?", Long.class, item.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_feedback WHERE item_id = ?", Long.class, item.getId())).isZero();
        assertThatThrownBy(() -> knowledgeService.restoreItem(item.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该知识条目");
    }
}
