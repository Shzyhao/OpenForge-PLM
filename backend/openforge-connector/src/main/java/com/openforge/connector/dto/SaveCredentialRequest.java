package com.openforge.connector.dto;

import lombok.Data;

/** 凭据创建/更新请求。secret 仅写入（更新时留空 = 保留原值）；任何响应不回显。 */
@Data
public class SaveCredentialRequest {

    private String credCode;

    private String credName;

    /** BASIC/BEARER/API_KEY_HEADER */
    private String authType;

    /** 明文凭据值，仅请求方向 */
    private String secret;

    /** 附加信息 JSON（如 {"headerName":"X-Api-Key"}） */
    private String extra;
}
