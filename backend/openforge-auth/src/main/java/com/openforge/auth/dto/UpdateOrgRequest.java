package com.openforge.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateOrgRequest {

    @Size(max = 128)
    private String orgName;

    private Integer sortOrder;
}
