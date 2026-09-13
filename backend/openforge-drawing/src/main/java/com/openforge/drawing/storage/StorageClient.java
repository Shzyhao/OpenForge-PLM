package com.openforge.drawing.storage;

import java.io.InputStream;

/** 图纸文件存储抽象（M2 本地磁盘；M3+ MinIO 实现沿用同 key 约定）。 */
public interface StorageClient {

    /** 保存文件，返回存储键（tenant/{tenantId}/{yyyyMMdd}/{uuid}{ext}，租户前缀目录隔离）。 */
    String save(String fileName, InputStream content) throws Exception;

    InputStream load(String storageKey) throws Exception;
}
