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
        // toAbsolutePath+normalize：删除路径防逃逸的 startsWith 判断要求两端同基准（相对路径下 "." 段会导致误判）
        this.baseDir = Path.of(storageDir).toAbsolutePath().normalize();
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

    @Override
    public void delete(String storageKey) throws Exception {
        Path target = baseDir.resolve(storageKey).toAbsolutePath().normalize();
        // 存储键虽为系统生成，彻底删除入口仍按根目录约束防目录逃逸
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法存储键: " + storageKey);
        }
        Files.deleteIfExists(target);
    }
}
