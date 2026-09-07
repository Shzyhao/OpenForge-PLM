package com.openforge.connector.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 供应商（集成编排器 MVP 设计 §12.1）：api_key 密文落库；enabled+priority 构成 ai-gateway 降级链。 */
@Data
@TableName("ai_provider")
public class AiProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerCode;

    private String providerName;

    /** OpenAI 兼容 base_url */
    private String baseUrl;

    /** AES-256-GCM base64(iv||ciphertext) */
    private String apiKeyEnc;

    private String model;

    private Integer timeoutMs;

    private Integer enabled;

    /** 降级链：数字越小越优先 */
    private Integer priority;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private Long updatedBy;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
