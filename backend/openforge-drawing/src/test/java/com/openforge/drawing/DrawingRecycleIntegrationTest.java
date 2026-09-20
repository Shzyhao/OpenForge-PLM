package com.openforge.drawing;

import com.openforge.common.api.BizException;
import com.openforge.drawing.client.AuthAuditClient;
import com.openforge.drawing.client.NumberClient;
import com.openforge.drawing.entity.DrawingInfo;
import com.openforge.drawing.service.DrawingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图纸回收站集成（v1.23 设计 §3，H2）：软删→回收站可见→恢复（DRW_RESTORE 审计对称）；
 * 草稿删除限制沿用 delete 语义。
 */
@SpringBootTest
class DrawingRecycleIntegrationTest {

    @Autowired
    private DrawingService drawingService;

    @MockBean
    private NumberClient numberClient;

    @MockBean
    private AuthAuditClient auditClient;

    private static final AtomicLong drwSeq = new AtomicLong(700);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("drawing")).thenAnswer(i -> "DW" + String.format("%010d", drwSeq.incrementAndGet()));
    }

    @Test
    @DisplayName("删除→回收站可见→恢复→业务侧可见；恢复审计与删除对称")
    void deleteRestoreRoundtrip() {
        DrawingInfo d = drawingService.create("回收站往返图纸", 9L);
        drawingService.delete(d.getId());

        assertThat(drawingService.trashed()).anyMatch(t -> t.getId().equals(d.getId()));
        assertThat(drawingService.page(1, 100, "回收站往返图纸", null, null).list()).isEmpty();

        DrawingInfo restored = drawingService.restore(d.getId(), 9L);
        assertThat(restored.getId()).isEqualTo(d.getId());
        assertThat(drawingService.page(1, 100, "回收站往返图纸", null, null).list())
                .anyMatch(x -> x.getId().equals(d.getId()));
        assertThat(drawingService.trashed()).noneMatch(t -> t.getId().equals(d.getId()));
        verify(auditClient).record(eq(9L), eq("DRW_RESTORE"), eq("DRAWING"), eq(d.getDrawingNumber()), eq("回收站往返图纸"));
    }

    @Test
    @DisplayName("未删除行与重复恢复按不存在应答")
    void restoreGuards() {
        DrawingInfo d = drawingService.create("未删除图纸", 9L);
        assertThatThrownBy(() -> drawingService.restore(d.getId(), 9L))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该图纸");

        drawingService.delete(d.getId());
        assertThat(drawingService.restore(d.getId(), 9L)).isNotNull();
        assertThatThrownBy(() -> drawingService.restore(d.getId(), 9L))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该图纸");
    }
}
