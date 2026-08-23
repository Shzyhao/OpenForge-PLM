package com.openforge.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateOrgRequest {

    @NotBlank
    @Size(max = 64)
    private String orgCode;

    @NotBlank
    @Size(max = 128)
    private String orgName;

    /** NULL = 根节点 */
    private Long parentId;

    private Integer sortOrder;
}
