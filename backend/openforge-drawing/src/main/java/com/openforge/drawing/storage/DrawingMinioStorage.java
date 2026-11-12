package com.openforge.drawing.storage;

import com.openforge.common.tenant.TenantContext;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;

/**
 * MinIO/S3 存储（v1.25 设计 §5）：openforge.drawing.storage=minio 时启用（默认 local）。
 * 键约定与 LocalDiskStorage 一致；类名带域前缀防 mono 同名 bean 冲突（实纱②）。
 */
@Component
@ConditionalOnProperty(name = "openforge.drawing.storage", havingValue = "minio")
public class DrawingMinioStorage implements StorageClient {

    private final MinioClient client;
    private final String bucket;

    public DrawingMinioStorage(@Value("${openforge.drawing.minio.endpoint}") String endpoint,
                               @Value("${openforge.drawing.minio.access-key}") String accessKey,
                               @Value("${openforge.drawing.minio.secret-key}") String secretKey,
                               @Value("${openforge.drawing.minio.bucket:openforge-drawing}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public String save(String fileName, InputStream content) throws Exception {
        String ext = "";
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0) {
            ext = fileName.substring(dot);
        }
        String storageKey = "tenant/" + TenantContext.getTenantId()
                + "/" + LocalDate.now() + "/" + UUID.randomUUID() + ext;
        try (InputStream in = content) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(storageKey)
                    .stream(in, -1, 10485760).build());
        }
        return storageKey;
    }

    @Override
    public InputStream load(String storageKey) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
    }

    @Override
    public void delete(String storageKey) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(storageKey).build());
    }
}
