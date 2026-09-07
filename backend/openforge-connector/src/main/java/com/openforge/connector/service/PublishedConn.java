package com.openforge.connector.service;

/** 已发布连接器快照（运行时执行的唯一依据；DRAFT 编辑不影响在途调用）。 */
public record PublishedConn(
        Long connId,
        String connCode,
        String connType,
        int version,
        String specJson) {
}
