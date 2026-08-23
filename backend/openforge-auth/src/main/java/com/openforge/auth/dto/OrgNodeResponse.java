package com.openforge.auth.dto;

import java.util.List;
import java.util.ArrayList;

public record OrgNodeResponse(
        Long id, String orgCode, String orgName, Long parentId,
        Integer sortOrder, List<OrgNodeResponse> children) {

    public static OrgNodeResponse of(com.openforge.auth.entity.SysOrg org) {
        return new OrgNodeResponse(org.getId(), org.getOrgCode(), org.getOrgName(),
                org.getParentId(), org.getSortOrder(), new ArrayList<>());
    }
}
