package com.openforge.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.knowledge.dto.PageResponse;
import com.openforge.knowledge.dto.SearchHit;
import com.openforge.knowledge.entity.KnowledgeFeedback;
import com.openforge.knowledge.entity.KnowledgeItem;
import com.openforge.knowledge.mapper.KnowledgeFeedbackMapper;
import com.openforge.knowledge.mapper.KnowledgeItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 知识库（开发文档 7.5 M5 子集）：条目 + 向量检索 + 反馈驱动的质量分闭环。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeItemMapper itemMapper;
    private final KnowledgeFeedbackMapper feedbackMapper;
    private final EmbeddingClient embeddingClient;
    private final InMemoryVectorStore vectorStore;

    public KnowledgeItem create(String title, String content, String tags,
                                String sourceType, String sourceRef, Long operatorId) {
        KnowledgeItem item = new KnowledgeItem();
        item.setTitle(title);
        item.setContent(content);
        item.setSummary(summarize(content));
        item.setTags(tags);
        item.setSourceType(sourceType == null ? "MANUAL" : sourceType);
        item.setSourceRef(sourceRef);
        item.setQualityScore(BigDecimal.valueOf(60));
        item.setUsageCount(0);
        item.setStatus("PUBLISHED");
        item.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        item.setCreatedBy(operatorId);
        item.setVectorId(vectorStore.add(embeddingClient.embed(title + "\n" + content)));
        itemMapper.insert(item);
        return item;
    }

    public PageResponse<KnowledgeItem> page(long page, long pageSize, String keyword) {
        LambdaQueryWrapper<KnowledgeItem> wrapper = new LambdaQueryWrapper<KnowledgeItem>()
                .orderByDesc(KnowledgeItem::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(KnowledgeItem::getTitle, keyword.trim())
                    .or().like(KnowledgeItem::getContent, keyword.trim());
        }
        Page<KnowledgeItem> result = itemMapper.selectPage(Page.of(page, Math.min(pageSize, 100)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 语义检索：向量 TopK（离线为词袋哈希降级，仍可测管道）。 */
    public List<SearchHit> search(String query, int topK) {
        List<InMemoryVectorStore.Scored> scored = vectorStore.search(
                embeddingClient.embed(query), Math.min(Math.max(topK, 1), 20));
        List<SearchHit> hits = new ArrayList<>();
        for (InMemoryVectorStore.Scored s : scored) {
            KnowledgeItem item = itemMapper.selectOne(new LambdaQueryWrapper<KnowledgeItem>()
                    .eq(KnowledgeItem::getVectorId, s.vectorId())
                    .eq(KnowledgeItem::getStatus, "PUBLISHED"));
            if (item != null) {
                hits.add(new SearchHit(item.getId(), item.getTitle(), item.getSummary(), s.score()));
            }
        }
        return hits;
    }

    /** 反馈闭环（开发文档 7.5 自适应）：ADOPT 提升 usage 与质量分，DISMISS 扣分。 */
    public void feedback(String queryText, Long itemId, String action, Long userId) {
        if (!List.of("CLICK", "DISMISS", "ADOPT", "RATE").contains(action)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知反馈类型: " + action);
        }
        KnowledgeFeedback fb = new KnowledgeFeedback();
        fb.setQueryText(queryText == null ? "" : queryText.substring(0, Math.min(queryText.length(), 500)));
        fb.setItemId(itemId);
        fb.setAction(action);
        fb.setUserId(userId);
        feedbackMapper.insert(fb);

        if (itemId == null) {
            return;
        }
        KnowledgeItem item = itemMapper.selectById(itemId);
        if (item == null) {
            return;
        }
        int delta = switch (action) {
            case "ADOPT" -> 2;
            case "CLICK" -> 1;
            case "DISMISS" -> -5;
            default -> 0;
        };
        if (delta > 0) {
            item.setUsageCount(item.getUsageCount() + 1);
        }
        item.setQualityScore(BigDecimal.valueOf(
                Math.max(0, Math.min(100, item.getQualityScore().doubleValue() + delta))));
        itemMapper.updateById(item);
        log.debug("knowledge feedback: item={} action={} quality={}", itemId, action, item.getQualityScore());
    }

    /** 摘要：M5 本地截断；LLM 摘要随 M6 接入（架构文档 4.1 知识自动摘要）。 */
    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String firstLine = content.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        return firstLine.length() > 120 ? firstLine.substring(0, 120) + "…" : firstLine;
    }
}
