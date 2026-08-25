package com.openforge.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 管理员创建用户（方案 D2）。 */
@Data
public class CreateUserRequest {

    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    @Size(min = 8, max = 64)
    private String password;

    @Size(max = 64)
    private String displayName;

    @Size(max = 128)
    private String email;

    private Long orgId;

    private List<Long> roleIds;
}
