package com.openforge.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateRoleRequest {

    @NotBlank
    @Size(max = 64)
    private String roleCode;

    @NotBlank
    @Size(max = 128)
    private String roleName;
}
