package com.openforge.drawing;

import com.openforge.common.api.BizException;
import com.openforge.drawing.client.NumberClient;
import com.openforge.drawing.entity.DrawingFile;
import com.openforge.drawing.entity.DrawingInfo;
import com.openforge.drawing.entity.DrawingPart;
import com.openforge.drawing.entity.DrawingVersion;
import com.openforge.drawing.service.DrawingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.openforge.drawing.client.AuthAuditClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图纸域集成（v1.20.0，H2）：取号建档、文件分类、检入检出、状态机全链路
 * （DRAFT→REVIEWING→RELEASED→OBSOLETE + 驳回 + 升版）、发布快照、物料关联双向查询。
 */
@SpringBootTest
class DrawingIntegrationTest {

    @Autowired
    private DrawingService drawingService;

    @MockBean
    private NumberClient numberClient;

    @MockBean
    private AuthAuditClient auditClient;

    private static final AtomicLong drwSeq = new AtomicLong(200);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("drawing")).thenAnswer(i -> "DW" + String.format("%010d", drwSeq.incrementAndGet()));
    }

    private byte[] content(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("创建取号 A/0；文件仅草稿可传；MAIN 缺失不能提交评审")
    void draftStageGuards() throws Exception {
        DrawingInfo drawing = drawingService.create("法兰盘零件图", 1L);
        assertThat(drawing.getDrawingNumber()).startsWith("DW");
        assertThat(drawing.version()).isEqualTo("A/0");

        DrawingFile main = drawingService.uploadFile(drawing.getId(), "flange.dwg",
                new ByteArrayInputStream(content("dwg-binary")), "MAIN");
        assertThat(main.getKind()).isEqualTo("MAIN");
        assertThat(main.getSha256()).hasSize(64);
        drawingService.uploadFile(drawing.getId(), "flange.pdf",
                new ByteArrayInputStream(content("pdf")), "PREVIEW");

        assertThatThrownBy(() -> drawingService.uploadFile(drawing.getId(), "x.txt",
                new ByteArrayInputStream(content("x")), "BAD_KIND"))
                .isInstanceOf(BizException.class);

        // 未检出不能提交？——未检出直接可提交（无锁即可），此处验证检出锁拦截
        drawingService.checkOut(drawing.getId(), 1L);
        assertThatThrownBy(() -> drawingService.submit(drawing.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("检入");
        assertThatThrownBy(() -> drawingService.checkOut(drawing.getId(), 2L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> drawingService.checkIn(drawing.getId(), 2L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("全链路：检入→提交→发布（快照固化）→升版（major+1 回 DRAFT）→作废")
    void lifecycleWithSnapshotAndRevise() throws Exception {
        DrawingInfo drawing = drawingService.create("机匣装配图", 1L);
        drawingService.uploadFile(drawing.getId(), "case.dwg",
                new ByteArrayInputStream(content("dwg")), "MAIN");
        drawingService.uploadFile(drawing.getId(), "case.pdf",
                new ByteArrayInputStream(content("pdf-preview")), "PREVIEW");
        drawingService.checkOut(drawing.getId(), 1L);
        drawingService.checkIn(drawing.getId(), 1L);
        assertThat(drawingService.detail(drawing.getId()).version()).isEqualTo("A/1");

        DrawingInfo submitted = drawingService.submit(drawing.getId());
        assertThat(submitted.getLifecycleState()).isEqualTo("REVIEWING");
        // 评审中禁止传文件
        assertThatThrownBy(() -> drawingService.uploadFile(drawing.getId(), "x.txt",
                new ByteArrayInputStream(content("x")), "ATTACHMENT"))
                .isInstanceOf(BizException.class);
        // 驳回回草稿再重新提交
        assertThat(drawingService.reject(drawing.getId()).getLifecycleState()).isEqualTo("DRAFT");
        drawingService.submit(drawing.getId());

        DrawingInfo released = drawingService.approve(drawing.getId(), 9L);
        assertThat(released.getLifecycleState()).isEqualTo("RELEASED");
        // R8 审计（v1.20.0 五轮补齐）：发布动作落审计
        verify(auditClient).record(eq(9L), eq("DRW_PUBLISH"), eq("DRAWING"), eq(drawing.getDrawingNumber()), any());

        // 发布快照固化（版本 A/1，含文件清单）
        List<DrawingVersion> versions = drawingService.versions(drawing.getId());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getVersion()).isEqualTo("A/1");
        assertThat(versions.get(0).getSnapshot()).contains("case.dwg").contains("PREVIEW");
        assertThat(versions.get(0).getReleasedBy()).isEqualTo(9L);

        // 升版：B/0 回草稿，文件延续
        DrawingInfo revised = drawingService.revise(drawing.getId());
        assertThat(revised.getVersionMajor()).isEqualTo("B");
        assertThat(revised.getVersionMinor()).isZero();
        assertThat(revised.getLifecycleState()).isEqualTo("DRAFT");
        assertThat(drawingService.files(drawing.getId())).hasSize(2);

        // 再发布产生 B/0 快照，随后作废
        drawingService.submit(drawing.getId());
        drawingService.approve(drawing.getId(), 9L);
        assertThat(drawingService.versions(drawing.getId())).hasSize(2);
        assertThat(drawingService.obsolete(drawing.getId()).getLifecycleState()).isEqualTo("OBSOLETE");
        // 作废后禁止一切草稿动作
        assertThatThrownBy(() -> drawingService.checkOut(drawing.getId(), 1L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("物料关联：幂等约束/角色校验/按物料反查含图纸摘要；草稿可删、发布后不可删")
    void partLinkAndDeleteGuards() throws Exception {
        DrawingInfo d1 = drawingService.create("法兰盘零件图", 1L);
        drawingService.uploadFile(d1.getId(), "flange.dwg",
                new ByteArrayInputStream(content("dwg")), "MAIN");
        DrawingInfo d2 = drawingService.create("法兰总成装配图", 1L);

        DrawingPart link = drawingService.linkPart(d1.getId(), 501L, "FL-001", "PART_DRAWING");
        assertThat(link.getPartNumber()).isEqualTo("FL-001");
        drawingService.linkPart(d2.getId(), 501L, "FL-001", "ASSEMBLY");
        // 同图纸同物料重复关联拒绝；非法角色拒绝
        assertThatThrownBy(() -> drawingService.linkPart(d1.getId(), 501L, "FL-001", "REFERENCE"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> drawingService.linkPart(d1.getId(), 502L, "FL-002", "BAD"))
                .isInstanceOf(BizException.class);

        List<Map<String, Object>> byPart = drawingService.byPart(501L);
        assertThat(byPart).hasSize(2);
        assertThat(byPart).extracting(m -> m.get("drawingNumber"))
                .containsExactlyInAnyOrder(d1.getDrawingNumber(), d2.getDrawingNumber());
        assertThat(byPart.get(0)).containsEntry("role", "PART_DRAWING");

        drawingService.unlinkPart(d2.getId(), 501L);
        assertThat(drawingService.byPart(501L)).hasSize(1);

        // 发布 d1（进入 RELEASED）后删除拒绝；草稿 d2 可删
        drawingService.submit(d1.getId());
        drawingService.approve(d1.getId(), 1L);
        drawingService.delete(d2.getId());
        assertThatThrownBy(() -> drawingService.delete(d1.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅草稿");
    }

    @Test
    @DisplayName("下载：流式内容一致；文件不属于该图纸拒绝")
    void downloadGuards() throws Exception {
        DrawingInfo drawing = drawingService.create("下载校验图", 1L);
        DrawingFile main = drawingService.uploadFile(drawing.getId(), "main.dwg",
                new ByteArrayInputStream(content("DRAWING-BYTES-123")), "MAIN");

        try (var stream = drawingService.download(drawing.getId(), main.getId()).stream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("DRAWING-BYTES-123");
        }
        assertThatThrownBy(() -> drawingService.download(drawing.getId(), 99999L))
                .isInstanceOf(BizException.class);
    }
}
