package com.openforge.drawing.storage;

import java.io.InputStream;

/** 图纸文件存储抽象（M2 本地磁盘；M3+ MinIO 实现沿用同 key 约定）。 */
public interface StorageClient {

    /** 保存文件，返回存储键（tenant/{tenantId}/{yyyyMMdd}/{uuid}{ext}，租户前缀目录隔离）。 */
    String save(String fileName, InputStream content) throws Exception;

    InputStream load(String storageKey) throws Exception;

    /** 按存储键删除（回收站彻底删除的文件 GC，v1.24；不存在时静默返回）。 */
    void delete(String storageKey) throws Exception;
    /** 列举全部存储键（孤儿对账用，v1.27 P2#10；不支持列举的实现抛 UnsupportedOperationException，调用方跳过）。 */
    default java.util.List<String> listAllKeys() {
        throw new UnsupportedOperationException("列举未实现");
    }

}
