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
}
