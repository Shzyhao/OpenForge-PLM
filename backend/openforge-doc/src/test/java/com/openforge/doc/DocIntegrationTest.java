package com.openforge.doc;

import com.openforge.common.api.BizException;
import com.openforge.doc.client.NumberClient;
import com.openforge.doc.entity.DocFile;
import com.openforge.doc.entity.DocInfo;
import com.openforge.doc.service.DocService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 文档域集成：取号建档、检入检出锁、小版本递增、文件上传 SHA256。 */
@SpringBootTest
class DocIntegrationTest {

    @Autowired
    private DocService docService;

    @MockBean
    private NumberClient numberClient;

    private static final AtomicLong docSeq = new AtomicLong(100);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("doc")).thenAnswer(i -> "D" + String.format("%010d", docSeq.incrementAndGet()));
    }

    @Test
    @DisplayName("创建文档自动取号；检出锁定后他人不可再检出/检入；检入小版本+1 解锁")
    void checkInOutFlow() {
        DocInfo doc = docService.create("法兰盘图纸", "DRAWING", 1L);
        assertThat(doc.getDocNumber()).startsWith("D");
        assertThat(doc.version()).isEqualTo("A/0");

        // 用户1检出 → 用户2不可再检出
        docService.checkOut(doc.getId(), 1L);
        assertThatThrownBy(() -> docService.checkOut(doc.getId(), 2L))
                .isInstanceOf(BizException.class);

        // 非检出人不可检入
        assertThatThrownBy(() -> docService.checkIn(doc.getId(), 2L))
                .isInstanceOf(BizException.class);

        // 检出人检入 → 小版本+1 解锁
        DocInfo checkedIn = docService.checkIn(doc.getId(), 1L);
        assertThat(checkedIn.version()).isEqualTo("A/1");
        assertThat(checkedIn.getCheckedOutBy()).isNull();
    }

    @Test
    @DisplayName("v1.31 P0-4 并发检出：两线程同时检出同一文档，恰好一人持有独占锁")
    void concurrentCheckOutSingleWinner() throws Exception {
        DocInfo doc = docService.create("并发检出测试", "DRAWING", 1L);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Object> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable actor = () -> {
            ready.countDown();
            try {
                go.await();
                docService.checkOut(doc.getId(), Thread.currentThread().getName().equals("co-1") ? 1L : 2L);
                outcomes.add("OK");
            } catch (Exception e) {
                outcomes.add(e);
            }
        };
        Thread t1 = new Thread(actor, "co-1");
        Thread t2 = new Thread(actor, "co-2");
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join(10_000);
        t2.join(10_000);

        // CAS 条件更新：恰好一人成功；另一人被拒——不再双双"独占"
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof Exception).count()).isEqualTo(1);
        DocInfo reloaded = docService.detail(doc.getId());
        assertThat(reloaded.getCheckedOutBy()).isIn(1L, 2L);
    }

    @Test
    @DisplayName("v1.31 P1-5 并发检入：两次同时检入只成功一次，小版本恰好 +1")
    void concurrentCheckInExactlyOnce() throws Exception {
        DocInfo doc = docService.create("并发检入测试", "DRAWING", 1L);
        docService.checkOut(doc.getId(), 1L);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Object> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable actor = () -> {
            ready.countDown();
            try {
                go.await();
                docService.checkIn(doc.getId(), 1L);
                outcomes.add("OK");
            } catch (Exception e) {
                outcomes.add(e);
            }
        };
        Thread t1 = new Thread(actor, "ci-1");
        Thread t2 = new Thread(actor, "ci-2");
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join(10_000);
        t2.join(10_000);

        // SQL 级自增条件更新：恰好一次成功；小版本只 +1（原 RMW 双成功会 +1/+1 或覆盖）
        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(docService.detail(doc.getId()).version()).isEqualTo("A/1");
    }

    @Test
    @DisplayName("上传文件：落盘+SHA256 入库；文件列表可查")
    void uploadFile() {
        DocInfo doc = docService.create("测试报告", "REPORT", 1L);
        byte[] content = "OpenForge doc content".getBytes(StandardCharsets.UTF_8);

        DocFile file = docService.uploadFile(doc.getId(), "report.txt",
                new ByteArrayInputStream(content));

        assertThat(file.getFileName()).isEqualTo("report.txt");
        assertThat(file.getFileSize()).isEqualTo(content.length);
        assertThat(file.getSha256()).hasSize(64);

        List<DocFile> files = docService.files(doc.getId());
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getStorageKey()).isNotBlank();
    }

    @Test
    @DisplayName("下载：字节级往返一致；文件不属于该文档拒绝（十轮补齐回读端点）")
    void downloadRoundtrip() throws Exception {
        DocInfo doc = docService.create("下载校验文档", "SPEC", 1L);
        byte[] content = ("roundtrip-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        DocFile file = docService.uploadFile(doc.getId(), "spec.txt",
                new ByteArrayInputStream(content));

        DocService.DownloadPayload payload = docService.download(doc.getId(), file.getId());
        byte[] downloaded = payload.stream().readAllBytes();
        assertThat(downloaded).isEqualTo(content);
        assertThat(payload.file().getFileName()).isEqualTo("spec.txt");

        // 跨文档/不存在文件 → 404 语义
        assertThatThrownBy(() -> docService.download(doc.getId(), 999999L))
                .isInstanceOf(BizException.class);
    }
}
