package com.openforge.auth.controller;

import com.openforge.auth.service.NotifyService;
import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通知内部摄取端点（v1.23 设计 §1.3）：事件总线关闭时 workflow 的 HTTP 回退通道。
 * X-Internal-Token 门禁；收件人与租户以 sys_user 记录为准，不信任调用方申报。
 */
@RestController
@RequestMapping("/api/v1/internal/notifications")
@RequiredArgsConstructor
public class InternalNotifyController {

    private final NotifyService notifyService;

    @Value("${openforge.internal.token:openforge-internal-dev-token}")
    private String internalToken;

    @PostMapping
    public ApiResponse<Void> ingest(@RequestBody IngestRequest body,
                                    @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternalToken(token);
        notifyService.ingest(body.getEventType(), body.getPayload() == null ? Map.of() : body.getPayload());
        return ApiResponse.ok();
    }

    private void requireInternalToken(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }

    @Data
    public static class IngestRequest {
        private String eventType;
        private Map<String, Object> payload;
    }
}
