package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String permCode;

    private String permName;

    /** MENU（界面权限）/ OPERATION（操作权限） */
    private String permType;

    /** 菜单父级（MENU 树用） */
    private Long parentId;

    private String description;

    private Integer sortOrder;
}
