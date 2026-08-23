package com.openforge.auth.service;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编号规则引擎 H2 集成验证：格式、递增、周期重置、并发唯一（行锁计数器）。
 */
@SpringBootTest
class NumberRuleIntegrationTest {

    @Autowired
    private NumberRuleService numberRuleService;

    private static final List<NumberRuleService.Segment> PART_LIKE =
            List.of(new NumberRuleService.Segment("CONST", "P", null, null),
                    new NumberRuleService.Segment("DATE", null, "yyyyMMdd", null),
                    new NumberRuleService.Segment("SEQ", null, null, 5));

    @Test
    @DisplayName("V5 内置 part 规则：格式 P-yyyyMMdd-00001 且连续递增")
    void builtinPartRuleFormatAndIncrement() {
        LocalDate today = LocalDate.now();
        String first = numberRuleService.nextNumber("part", today);
        String second = numberRuleService.nextNumber("part", today);

        // 同一测试期内可能已有其他用例取号，只验证格式与严格递增
        assertThat(first).matches("P" + today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "\\d{5}");
        assertThat(Long.parseLong(second.substring(first.length() - 5)))
                .isEqualTo(Long.parseLong(first.substring(first.length() - 5)) + 1);
    }

    @Test
    @DisplayName("周期重置：DAILY 规则跨日后流水号归 1")
    void dailyResetRestartsSequence() {
        numberRuleService.createRule("test_daily", "日重置测试", PART_LIKE, "DAILY");
        LocalDate day1 = LocalDate.of(2026, 8, 23);
        LocalDate day2 = LocalDate.of(2026, 8, 24);

        numberRuleService.nextNumber("test_daily", day1);
        numberRuleService.nextNumber("test_daily", day1); // day1 已到 2
        String firstOfDay2 = numberRuleService.nextNumber("test_daily", day2);

        assertThat(firstOfDay2).endsWith("00001"); // 新周期从 1 开始
        // 测试规则与计数器留在 H2 内存库中无害（各用例 rule_key 独立）
    }

    @Test
    @DisplayName("并发取号 60 次：行锁保证全部唯一")
    void concurrentNextNumbersAreUnique() throws Exception {
        numberRuleService.createRule("test_conc", "并发测试", PART_LIKE, "NONE");
        int threads = 12, perThread = 5, total = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(total);
        Set<String> results = ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                ready.countDown();
                try {
                    ready.await(); // 全线程就绪后同时开跑，最大化竞争
                    for (int i = 0; i < perThread; i++) {
                        results.add(numberRuleService.nextNumber("test_conc"));
                        done.countDown();
                    }
                } catch (Exception e) {
                    error.compareAndSet(null, e); // 显式收集，避免线程静默死亡导致误判超时
                }
            });
        }
        assertThat(done.await(60, TimeUnit.SECONDS))
                .as("并发取号应在 60s 内完成（若失败请检查 error）").isTrue();
        pool.shutdown();
        assertThat(error.get()).as("并发线程异常").isNull();

        assertThat(results).hasSize(total); // 无一重复
    }

    @Test
    @DisplayName("规则校验：无 SEQ 段或多个 SEQ 段拒绝创建")
    void segmentValidation() {
        List<NumberRuleService.Segment> noSeq =
                List.of(new NumberRuleService.Segment("CONST", "X", null, null));
        List<NumberRuleService.Segment> twoSeq = List.of(
                new NumberRuleService.Segment("SEQ", null, null, 3),
                new NumberRuleService.Segment("SEQ", null, null, 3));

        assertThatThrownBy(() -> numberRuleService.createRule("bad1", "无流水", noSeq, "NONE"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> numberRuleService.createRule("bad2", "双流水", twoSeq, "NONE"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("未知规则取号：抛 NUMBER_RULE_NOT_FOUND")
    void unknownRuleShouldFail() {
        assertThatThrownBy(() -> numberRuleService.nextNumber("no_such_rule"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NUMBER_RULE_NOT_FOUND));
    }
}
