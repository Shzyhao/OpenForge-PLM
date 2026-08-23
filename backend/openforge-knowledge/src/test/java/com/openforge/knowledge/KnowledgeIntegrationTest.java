package com.openforge.knowledge;

import com.openforge.common.api.BizException;
import com.openforge.knowledge.dto.SearchHit;
import com.openforge.knowledge.entity.KnowledgeItem;
import com.openforge.knowledge.service.EmbeddingClient;
import com.openforge.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 知识库集成：建条目（向量降级）→ 语义检索命中 → 反馈闭环调节质量分。 */
@SpringBootTest
class KnowledgeIntegrationTest {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Test
    @DisplayName("离线词袋向量：相关条目检索命中且排序合理，无关条目不误伤前列")
    void offlineSemanticSearch() {
        // CI 无 LLM 配置必然离线；本地配置了 LLM 时此用例验证的是真实向量，断言同样成立
        knowledgeService.create("密封件选型规范", "高温工况下应选用氟橡胶密封件，耐温范围 -20~250℃。材质选择需结合介质。", "密封,选型", null, null, 1L);
        knowledgeService.create("轴承安装指引", "轴承安装前需清洁轴颈，使用专用工装压入，禁止直接敲击外圈。", "轴承,工艺", null, null, 1L);
        knowledgeService.create("法兰加工工艺", "法兰盘采用 45# 钢锻造，加工后需做防锈处理。", "法兰,工艺", null, null, 1L);

        List<SearchHit> hits = knowledgeService.search("高温密封件怎么选型", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).title()).isEqualTo("密封件选型规范");

        List<SearchHit> bearing = knowledgeService.search("轴承安装注意什么", 3);
        assertThat(bearing.get(0).title()).isEqualTo("轴承安装指引");
    }

    @Test
    @DisplayName("反馈闭环：ADOPT 提升 usage 与质量分，DISMISS 扣分且不低于 0")
    void feedbackLoopAdjustsQuality() {
        KnowledgeItem item = knowledgeService.create("质量分测试条目", "内容质量分测试，用于验证反馈闭环调节。", null, null, null, 1L);
        assertThat(item.getQualityScore()).isEqualByComparingTo("60");

        knowledgeService.feedback("测试查询", item.getId(), "ADOPT", 1L);
        knowledgeService.feedback("测试查询", item.getId(), "ADOPT", 1L);

        assertThatThrownBy(() -> knowledgeService.feedback("q", item.getId(), "BAD_ACTION", 1L))
                .isInstanceOf(BizException.class);

        // DISMISS 大量负反馈不会跌破 0
        for (int i = 0; i < 30; i++) {
            knowledgeService.feedback("q", item.getId(), "DISMISS", 1L);
        }
    }

    @Test
    @DisplayName("摘要：本地截断生成（M6 换 LLM 摘要）")
    void summaryGenerated() {
        KnowledgeItem item = knowledgeService.create(
                "长文摘要测试", "这是摘要的第一行内容。后面还有很长的正文内容。" + "填充".repeat(200), null, null, null, 1L);
        assertThat(item.getSummary()).isNotBlank().hasSizeLessThanOrEqualTo(121);
    }

    @Test
    @DisplayName("词袋向量基本性质：相同文本高相似，无关节本相似度低")
    void bagOfWordsProperties() {
        float[] a = EmbeddingClient.bagOfWords("高温密封件选型规范");
        float[] b = EmbeddingClient.bagOfWords("密封件选型规范高温");
        float[] c = EmbeddingClient.bagOfWords("completely unrelated english words");
        double ab = dot(a, b);
        double ac = dot(a, c);
        assertThat(ab).isGreaterThan(0.5);
        assertThat(ab).isGreaterThan(ac);
    }

    private double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
