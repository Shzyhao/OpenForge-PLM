package com.openforge.change;

import com.openforge.common.api.BizException;
import com.openforge.change.client.MaterialClient;
import com.openforge.change.client.NumberClient;
import com.openforge.change.client.WorkflowClient;
import com.openforge.change.dto.EcrRequest;
import com.openforge.change.entity.ChangeRequest;
import com.openforge.change.service.EcrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 变更单回收站集成（v1.25 设计 §4，H2）：SUBMITTED（审批在途）不可删；终态可删→恢复→purge。
 */
@SpringBootTest
class ChangeRecycleIntegrationTest {

    @Autowired
    private EcrService ecrService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private NumberClient numberClient;

    @MockBean
    private WorkflowClient workflowClient;

    @MockBean
    private MaterialClient materialClient;

    private static final AtomicLong seq = new AtomicLong(900);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("ecr")).thenAnswer(i -> "ECR" + String.format("%010d", seq.incrementAndGet()));
    }

    private ChangeRequest create(String title) {
        EcrRequest r = new EcrRequest();
        r.setTitle(title);
        r.setReason("回收站测试");
        r.setUrgency("NORMAL");
        return ecrService.create(r, 1L);
    }

    @Test
    @DisplayName("SUBMITTED（在途）删除被拒；终态可删→回收站→恢复→purge 行物理消失")
    void inFlightGuardAndRoundtrip() {
        ChangeRequest ecr = create("在途变更单");
        // create 即 SUBMITTED（在途）→ 删除被拒
        assertThatThrownBy(() -> ecrService.delete(ecr.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("审批在途的变更单不可删除");

        // 模拟流程终态（APPROVED）→ 走回收站全链路
        jdbc.update("UPDATE change_request SET state = 'APPROVED' WHERE id = ?", ecr.getId());
        ecrService.delete(ecr.getId());
        assertThat(ecrService.trashed()).anyMatch(t -> t.getId().equals(ecr.getId()));

        ChangeRequest restored = ecrService.restore(ecr.getId());
        assertThat(restored.getId()).isEqualTo(ecr.getId());
        assertThat(ecrService.trashed()).noneMatch(t -> t.getId().equals(ecr.getId()));

        // purge 面：行物理消失，restore 404
        ecrService.delete(ecr.getId());
        ecrService.purge(ecr.getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_request WHERE id = ?", Long.class, ecr.getId())).isZero();
        assertThatThrownBy(() -> ecrService.restore(ecr.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该变更单");
    }
}
