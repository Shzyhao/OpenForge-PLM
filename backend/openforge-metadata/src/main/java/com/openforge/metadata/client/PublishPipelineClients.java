package com.openforge.metadata.client;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 发布流水线下游客户端（F2 设计 5）：auth 权限点创建 / knowledge Schema 同步 / AI 网关表登记。
 * 全部走内部令牌直连（不经网关）。失败语义：
 * - 权限点创建失败 → 阻断发布（动态 CRUD 可用性的前提）；
 * - knowledge / AI 登记 → 由调用方按尽力而为处理（增值能力，不阻塞业务发布）。
 */
@Slf4j
@Component
public class PublishPipelineClients {

    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Object>> OBJ_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient authClient;
    private final RestClient knowledgeClient;
    private final RestClient aiClient;
    private final String internalToken;

    public PublishPipelineClients(
            @Value("${openforge.security.auth-base-url}") String authBaseUrl,
            @Value("${openforge.integration.knowledge-base-url:http://localhost:8086}") String knowledgeBaseUrl,
            @Value("${openforge.integration.ai-base-url:http://localhost:8001}") String aiBaseUrl,
            @Value("${openforge.security.internal-token}") String internalToken) {
        this.internalToken = internalToken;
        this.authClient = RestClient.builder().baseUrl(authBaseUrl).build();
        this.knowledgeClient = RestClient.builder().baseUrl(knowledgeBaseUrl).build();
        // AI 网关内部端点不在 /api/v1/ai/** 网关路由面内
        this.aiClient = RestClient.builder().baseUrl(aiBaseUrl).build();
    }

    /** 幂等创建权限点并绑定角色；失败抛 BizException 阻断发布。 */
    public void ensurePermission(String permCode, String permName, List<String> bindRoleCodes) {
        ApiResponse<Map<String, Object>> response;
        try {
            response = authClient.post()
                    .uri("/api/v1/internal/permissions")
                    .header("X-Internal-Token", internalToken)
                    .body(Map.of("permCode", permCode, "permName", permName,
                            "bindRoleCodes", bindRoleCodes))
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "权限服务不可用，发布中止: " + e.getMessage());
        }
        if (response == null || response.getCode() != 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "权限点创建失败，发布中止: " + (response == null ? "无响应" : response.getMessage()));
        }
    }

    /** 表结构描述入知识库（sourceType=SCHEMA），供 AI 溯源与 RAG 检索。 */
    public void syncSchemaItem(String title, String content, String sourceRef) {
        try {
            ApiResponse<Object> response = knowledgeClient.post()
                    .uri("/api/v1/knowledge/internal/items")
                    .header("X-Internal-Token", internalToken)
                    .body(Map.of("title", title, "content", content,
                            "tags", "schema,动态对象", "sourceType", "SCHEMA", "sourceRef", sourceRef))
                    .retrieve()
                    .body(OBJ_TYPE);
            if (response == null || response.getCode() != 0) {
                log.warn("Schema 知识同步返回异常（不阻塞发布）: sourceRef={}, resp={}",
                        sourceRef, response == null ? "无响应" : response.getMessage());
            }
        } catch (Exception e) {
            log.warn("知识服务不可用，Schema 同步跳过（不阻塞发布）: {}", e.getMessage());
        }
    }

    /** AI 网关表白名单 + 描述登记（nl2sql 即刻可查）。 */
    public void registerAiTable(String table, String description) {
        try {
            aiClient.post()
                    .uri("/internal/tables")
                    .header("X-Internal-Token", internalToken)
                    .body(Map.of("table", table, "description", description))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("AI 网关不可用，表登记跳过（不阻塞发布，下次发布重试）: {}", e.getMessage());
        }
    }

    /**
     * 注册 EXTENSION 模块（A4-4：动态对象发布即注册——路由/菜单/模块管理三面同构原生服务）。
     * 与权限点同语义：失败阻断发布。
     */
    public void registerExtensionModule(Long objectId, String objectKey, String displayName,
                                        int version, String serviceUri) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("moduleKey", "dyn:" + objectKey);
        body.put("moduleType", "EXTENSION");
        body.put("displayName", displayName);
        body.put("version", String.valueOf(version));
        body.put("routes", List.of("/api/v1/objects/" + objectKey));
        body.put("menu", List.of(Map.of(
                "path", "/meta/data?object=" + objectKey, "title", displayName)));
        body.put("dependencies", List.of());
        body.put("serviceUri", serviceUri);
        body.put("ownerRef", objectId);
        try {
            ApiResponse<Map<String, Object>> response = authClient.post()
                    .uri("/api/v1/internal/modules")
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .body(MAP_TYPE);
            if (response == null || response.getCode() != 0) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "EXTENSION 模块注册失败，发布中止: " + (response == null ? "无响应" : response.getMessage()));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册中心不可用，发布中止: " + e.getMessage());
        }
    }
}
