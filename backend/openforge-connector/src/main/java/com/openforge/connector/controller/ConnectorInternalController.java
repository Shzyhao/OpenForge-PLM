package com.openforge.connector.controller;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.connector.dto.InvokeRequest;
import com.openforge.connector.dto.InvokeResponse;
import com.openforge.connector.service.ConnectorDefinitionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部调用端点（服务间直连，不经网关）：X-Internal-Token 强制校验，
 * 租户经调用方透传 X-User-Tenant（TenantHeaderFilter 解析）。
 */
@RestController
@RequestMapping("/internal/connector")
public class ConnectorInternalController {

    private final ConnectorDefinitionService definitionService;
    private final String internalToken;

    public ConnectorInternalController(ConnectorDefinitionService definitionService,
                                       @Value("${openforge.security.internal-token:openforge-internal-dev-token}")
                                       String internalToken) {
        this.definitionService = definitionService;
        this.internalToken = internalToken;
    }

    @PostMapping("/invoke/{connCode}")
    public ApiResponse<InvokeResponse> invoke(
            @PathVariable String connCode,
            @RequestBody(required = false) InvokeRequest request,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        return ApiResponse.ok(definitionService.invoke(connCode,
                request == null || request.getParams() == null ? Map.of() : request.getParams()));
    }

    private void requireInternal(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }
}
