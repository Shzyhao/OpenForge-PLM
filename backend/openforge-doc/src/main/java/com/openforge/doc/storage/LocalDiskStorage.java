package com.openforge.doc.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** 本地磁盘存储（M2）：按日期目录 + UUID 文件名落盘，元数据在 doc_file 表。 */
@Component
public class LocalDiskStorage implements StorageClient {

    private final Path baseDir;

    public LocalDiskStorage(@Value("${openforge.doc.storage-dir:./data/doc-files}") String storageDir) throws IOException {
        this.baseDir = Path.of(storageDir);
        Files.createDirectories(baseDir);
    }

    @Override
    public String save(String fileName, InputStream content) throws Exception {
        String ext = "";
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0) {
            ext = fileName.substring(dot);
        }
        // 租户前缀目录隔离（架构文档 7.3：文件按租户隔离；未来 MinIO 实现沿用同 key 约定）
        String storageKey = "tenant/" + com.openforge.common.tenant.TenantContext.getTenantId()
                + "/" + java.time.LocalDate.now() + "/" + UUID.randomUUID() + ext;
        Path target = baseDir.resolve(storageKey);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return storageKey;
    }

    @Override
    public InputStream load(String storageKey) throws Exception {
        return Files.newInputStream(baseDir.resolve(storageKey));
    }
}
