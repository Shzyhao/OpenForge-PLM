package com.openforge.connector.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 凭据（密文落库；API 永不回显明文；连接器 spec 以 credCode 引用）。 */
@Data
@TableName("conn_credential")
public class ConnCredential {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String credCode;

    private String credName;

    /** BASIC/BEARER/API_KEY_HEADER/JDBC_PASSWORD（刀2） */
    private String authType;

    /** AES-256-GCM base64(iv||ciphertext) */
    private String secretCipher;

    /** 如 API_KEY 的 headerName */
    private String extraJson;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private Long updatedBy;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
