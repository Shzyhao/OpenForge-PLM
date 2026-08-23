package com.openforge.material.dto;

import java.util.ArrayList;
import java.util.List;

public record CategoryNodeResponse(
        Long id, String categoryCode, String categoryName, Long parentId,
        Integer sortOrder, List<CategoryNodeResponse> children) {

    public static CategoryNodeResponse of(com.openforge.material.entity.PartCategory c) {
        return new CategoryNodeResponse(c.getId(), c.getCategoryCode(), c.getCategoryName(),
                c.getParentId(), c.getSortOrder(), new ArrayList<>());
    }
}
