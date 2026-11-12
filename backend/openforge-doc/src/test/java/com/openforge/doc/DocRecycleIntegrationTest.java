package com.openforge.doc;

import com.openforge.common.api.BizException;
import com.openforge.doc.client.NumberClient;
import com.openforge.doc.entity.DocFile;
import com.openforge.doc.entity.DocInfo;
import com.openforge.doc.service.DocService;
import com.openforge.doc.storage.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 文档删除入口 + 回收站 + 彻底删除（v1.24 设计 §2/§3，H2）：
 * 仅草稿未检出可删；恢复编号冲突拒绝；purge 走 StorageClient 文件 GC + 行删除。
 */
@SpringBootTest
class DocRecycleIntegrationTest {

    @Autowired
    private DocService docService;

    @Autowired
    private StorageClient storageClient;

    @MockBean
    private NumberClient numberClient;

    private static final AtomicLong docSeq = new AtomicLong(700);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("doc")).thenAnswer(i -> "D" + String.format("%010d", docSeq.incrementAndGet()));
    }

    @Test
    @DisplayName("删除→回收站→恢复往返；已检出文档拒绝删除")
    void deleteRestoreGuards() {
        DocInfo d = docService.create("回收往返文档", "GENERAL", 1L);
        docService.checkOut(d.getId(), 1L);
        assertThatThrownBy(() -> docService.delete(d.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("检出中的文档不可删除");
        docService.checkIn(d.getId(), 1L);

        docService.delete(d.getId());
        assertThat(docService.trashed()).anyMatch(t -> t.getId().equals(d.getId()));
        DocInfo restored = docService.restore(d.getId());
        assertThat(restored.getId()).isEqualTo(d.getId());
        assertThat(docService.trashed()).noneMatch(t -> t.getId().equals(d.getId()));
    }

    @Test
    @DisplayName("彻底删除：文件字节经 StorageClient GC 后不可再读，行清除后 restore 404")
    void purgeWithFileGc() throws Exception {
        DocInfo d = docService.create("purge文档", "GENERAL", 1L);
        DocFile f = docService.uploadFile(d.getId(), "main.txt",
                new ByteArrayInputStream("v1.24-gc".getBytes(StandardCharsets.UTF_8)));
        String storageKey = f.getStorageKey();
        // 前置：文件可读
        storageClient.load(storageKey).close();

        docService.delete(d.getId());
        DocInfo purged = docService.purge(d.getId());
        assertThat(purged.getId()).isEqualTo(d.getId());
        assertThat(docService.trashed()).noneMatch(t -> t.getId().equals(d.getId()));

        // 文件字节已 GC
        assertThatThrownBy(() -> storageClient.load(storageKey)).isInstanceOf(Exception.class);
        // 行已彻底删除：restore 按 404 语义拒绝
        assertThatThrownBy(() -> docService.restore(d.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该文档");
    }
}
