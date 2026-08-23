package com.openforge.material.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCategoryRequest {

    @NotBlank
    @Size(max = 64)
    private String categoryCode;

    @NotBlank
    @Size(max = 128)
    private String categoryName;

    private Long parentId;

    private Integer sortOrder;
}
