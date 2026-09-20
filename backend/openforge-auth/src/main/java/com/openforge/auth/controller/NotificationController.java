package com.openforge.auth.controller;

import com.openforge.auth.dto.PageResponse;
import com.openforge.auth.entity.NotifyMessage;
import com.openforge.auth.service.NotifyService;
import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 站内通知收件箱（v1.23 设计 §1.5）：数据天然按收件人隔离，无需权限点。 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotifyService notifyService;

    @GetMapping
    public ApiResponse<PageResponse<NotifyMessage>> inbox(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        return ApiResponse.ok(notifyService.inbox(currentUserId(request), unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(HttpServletRequest request) {
        return ApiResponse.ok(Map.of("count", notifyService.unreadCount(currentUserId(request))));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id, HttpServletRequest request) {
        notifyService.markRead(id, currentUserId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead(HttpServletRequest request) {
        notifyService.markAllRead(currentUserId(request));
        return ApiResponse.ok();
    }

    private Long currentUserId(HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
        if (header == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未识别当前用户");
        }
        try {
            return Long.valueOf(header);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户身份无效");
        }
    }
}
