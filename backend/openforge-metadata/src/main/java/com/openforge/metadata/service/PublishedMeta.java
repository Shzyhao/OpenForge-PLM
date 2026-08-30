package com.openforge.metadata.service;

import com.openforge.metadata.entity.MetaField;
import com.openforge.metadata.entity.MetaObject;

import java.util.List;

/** 发布对象元数据快照（对象 + 有序字段），供动态记录运行时白名单校验与 SQL 组装。 */
public record PublishedMeta(MetaObject object, List<MetaField> fields) {
}
