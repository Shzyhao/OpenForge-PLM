package com.openforge.change.client;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物料服务客户端（刀2 统一变更中心）：变更单创建期校验/影响清单、审批通过后执行动作。
 * 直连 material 内部接口（不经网关），共享内网令牌 + 租户透传。
 */
@Component
public class MaterialClient {

    public record BomView(Long id, String bomNumber, String version, String lifecycleState, Long parentPartId) {
    }

    public record LineView(Long id, Long bomId, Integer position, Long childPartId,
                           String childPartNumber, String childPartName) {
    }

    public record PartView(Long id, String partNumber, String name, String lifecycleState) {
    }

    public record SubstituteInput(Long substitutePartId, Integer priority, BigDecimal qtyCoefficient) {
    }

    private static final ParameterizedTypeReference<ApiResponse<BomView>> BOM_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<LineView>> LINE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<PartView>> PART_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<Map<String, Object>>>> MAP_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<SubstituteInput>>> SUB_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final com.openforge.security.ModuleAvailabilityClient moduleAvailability;

    public MaterialClient(@Value("${openforge.material.base-url}") String materialBaseUrl,
                          @Value("${openforge.security.internal-token}") String internalToken,
                          com.openforge.security.ModuleAvailabilityClient moduleAvailability) {
        this.restClient = RestClient.builder()
                .baseUrl(materialBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
        this.moduleAvailability = moduleAvailability;
    }

    /** 变更执行失败抛 BizException，由调用方落 apply_state=FAILED。 */
    public BomView bom(Long id) {
        moduleAvailability.ensureAvailable("material");
        ApiResponse<BomView> resp = get("/api/v1/internal/boms/{id}", BOM_TYPE, Map.of("id", id));
        return resp.getData();
    }

    public LineView line(Long lineId) {
        moduleAvailability.ensureAvailable("material");
        ApiResponse<LineView> resp = get("/api/v1/internal/boms/lines/{lineId}", LINE_TYPE, Map.of("lineId", lineId));
        return resp.getData();
    }

    public List<SubstituteInput> substitutes(Long bomId, Long lineId) {
        moduleAvailability.ensureAvailable("material");
        ApiResponse<List<SubstituteInput>> resp = get("/api/v1/internal/boms/{bomId}/lines/{lineId}/substitutes",
                SUB_LIST_TYPE, Map.of("bomId", bomId, "lineId", lineId));
        return resp.getData() == null ? List.of() : resp.getData();
    }

    public PartView part(Long id) {
        moduleAvailability.ensureAvailable("material");
        ApiResponse<PartView> resp = get("/api/v1/internal/parts/{id}", PART_TYPE, Map.of("id", id));
        return resp.getData();
    }

    public List<Map<String, Object>> whereUsed(Long partId) {
        moduleAvailability.ensureAvailable("material");
        ApiResponse<List<Map<String, Object>>> resp = get("/api/v1/internal/boms/where-used?partId={partId}",
                MAP_LIST_TYPE, Map.of("partId", partId));
        return resp.getData() == null ? List.of() : resp.getData();
    }

    /** 审批通过执行：全量替换发布版 BOM 指定行的替代组（last_change_id=变更单）。 */
    public void applySubstitutes(Long lineId, List<SubstituteInput> after, Long changeId) {
        moduleAvailability.ensureAvailable("material");
        post("/api/v1/internal/boms/lines/{lineId}/substitutes/apply",
                Map.of("lineId", lineId),
                Map.of("substitutes", after == null ? List.of() : after, "changeId", changeId == null ? 0 : changeId));
    }

    /** 审批通过执行：物料禁用（RELEASED→FROZEN）/启用（FROZEN→RELEASED）。 */
    public PartView applyPartLifecycle(Long partId, String targetState) {
        moduleAvailability.ensureAvailable("material");
        ApiResponse<PartView> resp = post("/api/v1/internal/parts/{id}/lifecycle",
                Map.of("id", partId), Map.of("target", targetState));
        return resp.getData();
    }

    private <T> ApiResponse<T> get(String uriTemplate, ParameterizedTypeReference<ApiResponse<T>> type,
                                   Map<String, ?> vars) {
        ApiResponse<T> resp;
        try {
            resp = restClient.get()
                    .uri(uriTemplate, vars)
                    .headers(this::forwardTenant)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "物料服务不可用: " + extractMessage(e));
        }
        if (resp == null || resp.getCode() != 0 || resp.getData() == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    resp == null ? "物料服务无响应" : "物料数据获取失败: " + resp.getMessage());
        }
        return resp;
    }

    private ApiResponse<PartView> post(String uriTemplate, Map<String, ?> vars, Object body) {
        ApiResponse<PartView> resp;
        try {
            resp = restClient.post()
                    .uri(uriTemplate, vars)
                    .headers(this::forwardTenant)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "物料服务调用失败: " + extractMessage(e));
        }
        if (resp == null || resp.getCode() != 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    resp == null ? "物料服务无响应" : "物料变更执行失败: " + resp.getMessage());
        }
        return resp;
    }

    /** 优先提取远端响应体里的业务原因（GlobalExceptionHandler JSON），退化为异常消息。 */
    private static String extractMessage(Exception e) {
        if (e instanceof org.springframework.web.client.RestClientResponseException rce) {
            String body = rce.getResponseBodyAsString();
            int idx = body.indexOf("\"message\":\"");
            if (idx >= 0) {
                int start = idx + "\"message\":\"".length();
                int end = body.indexOf('"', start);
                if (end > start) {
                    return body.substring(start, end);
                }
            }
        }
        return e.getMessage();
    }

    /** 服务间调用透传租户（内部端点不经网关，X-User-Tenant 由调用方显式注入）。 */
    private void forwardTenant(org.springframework.http.HttpHeaders headers) {
        headers.set(com.openforge.common.tenant.TenantHeaderFilter.HEADER_USER_TENANT,
                String.valueOf(TenantContext.getTenantId()));
    }
}
