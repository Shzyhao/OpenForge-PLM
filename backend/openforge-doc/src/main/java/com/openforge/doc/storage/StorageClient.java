package com.openforge.doc.storage;

import java.io.InputStream;

/**
 * 文件存储抽象。M2 为本地磁盘实现；M3 增加 MinIO/S3 实现（架构文档 7.1）。
 */
public interface StorageClient {

    /** 保存并返回存储键（相对路径或对象键）。 */
    String save(String fileName, InputStream content) throws Exception;

    /** 按存储键读取。 */
    InputStream load(String storageKey) throws Exception;
}
