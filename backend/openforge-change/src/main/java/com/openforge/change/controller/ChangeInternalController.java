package com.openforge.change.controller;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.change.service.EcrService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 变更域服务间内部端点（不经网关，X-Internal-Token 门禁；租户经 X-User-Tenant 透传）。
 * v1.22 ECO 联动：drawing.released 事件总线关闭时的同步 HTTP 回退入口。
 */
@RestController
@RequestMapping("/api/v1/internal/change")
public class ChangeInternalController {

    private final EcrService ecrService;
    private final String internalToken;

    public ChangeInternalController(EcrService ecrService,
                                    @Value("${openforge.security.internal-token:openforge-internal-dev-token}")
                                    String internalToken) {
        this.ecrService = ecrService;
        this.internalToken = internalToken;
    }

    @PostMapping("/drawing-released")
    public ApiResponse<Map<String, Object>> drawingReleased(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        return ApiResponse.ok(ecrService.autoCreateFromDrawing(payload));
    }

    private void requireInternal(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }
}
