package com.openforge.auth.service;

import com.openforge.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编号规则跨租户可见（R10）：sys_number_rule 种子全部 tenant 0 且不在 GLOBAL_TABLES，
 * 非零租户取号必失败（NUMBER_RULE_NOT_FOUND）——新租户无法创建任何带编号实体。
 * 修复后规则为平台模板（GLOBAL_TABLES），计数器保持全局递增。
 */
@SpringBootTest
class NumberRuleTenantVisibilityTest {

    @Autowired
    private NumberRuleService numberRuleService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("非零租户按平台规则取号成功（修复前 NUMBER_RULE_NOT_FOUND）")
    void nonZeroTenantCanTakeNumber() {
        TenantContext.setTenantId(1L);
        String number = numberRuleService.nextNumber("ecr");
        assertThat(number).startsWith("ECR").hasSizeGreaterThan(3);
    }
}
