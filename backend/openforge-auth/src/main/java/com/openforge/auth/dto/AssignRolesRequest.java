package com.openforge.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class AssignRolesRequest {

    /** 空列表表示清空该用户全部角色 */
    @NotNull
    private List<@NotNull Long> roleIds;
}
