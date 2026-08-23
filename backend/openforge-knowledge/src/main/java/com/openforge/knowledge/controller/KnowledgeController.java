package com.openforge.knowledge.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.knowledge.dto.PageResponse;
import com.openforge.knowledge.dto.SearchHit;
import com.openforge.knowledge.entity.KnowledgeItem;
import com.openforge.knowledge.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @Value("${openforge.internal.token:openforge-internal-dev-token}")
    private String internalToken;

    @PostMapping("/items")
    @RequirePermission("knowledge:manage")
    public ApiResponse<KnowledgeItem> create(@RequestBody CreateItemRequest request,
                                             HttpServletRequest httpRequest) {
        return ApiResponse.ok(knowledgeService.create(request.getTitle(), request.getContent(),
                request.getTags(), request.getSourceType(), request.getSourceRef(),
                currentUserId(httpRequest)));
    }

    /** 服务间知识沉淀入口（变更结项/文档发布等场景调用，X-Internal-Token 防护）。 */
    @PostMapping("/internal/items")
    public ApiResponse<KnowledgeItem> internalCreate(@RequestBody CreateItemRequest request,
                                                     @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        return ApiResponse.ok(knowledgeService.create(request.getTitle(), request.getContent(),
                request.getTags(), request.getSourceType(), request.getSourceRef(), null));
    }

    @GetMapping("/items")
    public ApiResponse<PageResponse<KnowledgeItem>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(knowledgeService.page(page, pageSize, keyword));
    }

    @GetMapping("/search")
    public ApiResponse<List<SearchHit>> search(@RequestParam String q,
                                               @RequestParam(defaultValue = "5") int topK) {
        return ApiResponse.ok(knowledgeService.search(q, topK));
    }

    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(@RequestBody FeedbackRequest request,
                                      HttpServletRequest httpRequest) {
        knowledgeService.feedback(request.getQueryText(), request.getItemId(),
                request.getAction(), currentUserId(httpRequest));
        return ApiResponse.ok();
    }

    private void requireInternal(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }

    private Long currentUserId(HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
        if (header == null) {
            return null;
        }
        try {
            return Long.valueOf(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    public static class CreateItemRequest {
        @NotBlank
        private String title;
        @NotBlank
        private String content;
        private String tags;
        private String sourceType;
        private String sourceRef;
    }

    @Data
    public static class FeedbackRequest {
        private String queryText;
        private Long itemId;
        @NotBlank
        private String action;
    }
}
