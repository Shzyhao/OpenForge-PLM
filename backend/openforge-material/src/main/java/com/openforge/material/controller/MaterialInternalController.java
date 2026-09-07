package com.openforge.material.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.dto.BomLineResponse;
import com.openforge.material.dto.SubstituteRequest;
import com.openforge.material.entity.Bom;
import com.openforge.material.entity.Part;
import com.openforge.material.service.BomService;
import com.openforge.material.service.PartService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 物料域服务间内部接口（刀2 统一变更中心执行通道）：仅限内网直连调用（不经网关路由），
 * 强制校验共享内网令牌 X-Internal-Token；租户经 X-User-Tenant 透传（TenantHeaderFilter 解析）。
 */
@RestController
@RequestMapping("/api/v1/internal")
public class MaterialInternalController {

    private final BomService bomService;
    private final PartService partService;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public MaterialInternalController(BomService bomService, PartService partService,
                                      ObjectMapper objectMapper,
                                      @Value("${openforge.security.internal-token:openforge-internal-dev-token}")
                                      String internalToken) {
        this.bomService = bomService;
        this.partService = partService;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    @GetMapping("/boms/{id}")
    public ApiResponse<Bom> bom(@PathVariable Long id,
                                @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        return ApiResponse.ok(bomService.requireBom(id));
    }

    @GetMapping("/boms/lines/{lineId}")
    public ApiResponse<BomLineResponse> line(@PathVariable Long lineId,
                                             @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        return ApiResponse.ok(bomService.lineDetail(lineId));
    }

    @GetMapping("/boms/{bomId}/lines/{lineId}/substitutes")
    public ApiResponse<List<BomLineResponse.SubstituteView>> substitutes(
            @PathVariable Long bomId, @PathVariable Long lineId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        return ApiResponse.ok(bomService.substitutes(bomId, lineId));
    }

    /** 变更单审批通过后的替代组应用（全量替换 + 重放校验 + last_change_id 回写）。 */
    @PostMapping("/boms/lines/{lineId}/substitutes/apply")
    public ApiResponse<Void> applySubstitutes(@PathVariable Long lineId,
                                              @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        List<SubstituteRequest> substitutes = objectMapper.convertValue(
                body.get("substitutes"), new TypeReference<>() {
                });
        Long changeId = body.get("changeId") == null ? null
                : Long.valueOf(String.valueOf(body.get("changeId")));
        bomService.applySubstituteChange(lineId, substitutes, changeId);
        return ApiResponse.ok();
    }

    @GetMapping("/boms/where-used")
    public ApiResponse<List<Map<String, Object>>> whereUsed(@RequestParam Long partId,
                                                            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        return ApiResponse.ok(bomService.whereUsed(partId));
    }

    @GetMapping("/parts/{id}")
    public ApiResponse<Part> part(@PathVariable Long id,
                                  @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        return ApiResponse.ok(partService.detail(id));
    }

    /** 变更单审批通过后的物料禁用/启用（RELEASED⇄FROZEN）。 */
    @PostMapping("/parts/{id}/lifecycle")
    public ApiResponse<Part> applyPartLifecycle(@PathVariable Long id,
                                                @RequestBody Map<String, String> body,
                                                @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        return ApiResponse.ok(partService.applyLifecycle(id, body.get("target"), null));
    }

    private void requireToken(String token) {
        if (internalToken == null || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部令牌缺失或不匹配");
        }
    }
}
