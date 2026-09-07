package com.openforge.connector.dto;

import com.openforge.connector.entity.ConnCredential;
import lombok.Data;

/** 凭据视图：永不回显 secret（密文不出服务层）。 */
@Data
public class CredentialResponse {

    private Long id;
    private String credCode;
    private String credName;
    private String authType;
    /** 附加信息原文（如 headerName），不含敏感值 */
    private String extra;

    public static CredentialResponse from(ConnCredential credential) {
        CredentialResponse response = new CredentialResponse();
        response.setId(credential.getId());
        response.setCredCode(credential.getCredCode());
        response.setCredName(credential.getCredName());
        response.setAuthType(credential.getAuthType());
        response.setExtra(credential.getExtraJson());
        return response;
    }
}
