package com.openforge.auth.controller;

import com.openforge.auth.entity.SysNumberRule;
import com.openforge.auth.service.NumberRuleService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/numbers")
public class NumberController {

    private final NumberRuleService numberRuleService;

    public NumberController(NumberRuleService numberRuleService) {
        this.numberRuleService = numberRuleService;
    }

    @GetMapping("/rules")
    @RequirePermission("number:manage")
    public ApiResponse<List<SysNumberRule>> list() {
        return ApiResponse.ok(numberRuleService.listRules());
    }

    /** 取号计数器水位（十轮改进：此前只能 SQL 手查）。 */
    @GetMapping("/counters")
    @RequirePermission("number:manage")
    public ApiResponse<List<com.openforge.auth.entity.SysNumberCounter>> counters() {
        return ApiResponse.ok(numberRuleService.listCounters());
    }

    @PostMapping("/rules")
    @RequirePermission("number:manage")
    public ApiResponse<SysNumberRule> create(@Valid @RequestBody CreateRuleRequest request) {
        return ApiResponse.ok(numberRuleService.createRule(
                request.getRuleKey(), request.getRuleName(),
                request.getSegments(), request.getResetPolicy()));
    }

    /** 停用/启用（2026-11-28 审查补齐：坏规则此前只能留着继续取号）。 */
    @PostMapping("/rules/{id}/enable")
    @RequirePermission("number:manage")
    public ApiResponse<SysNumberRule> enable(@PathVariable Long id) {
        return ApiResponse.ok(numberRuleService.toggleRule(id, true));
    }

    @PostMapping("/rules/{id}/disable")
    @RequirePermission("number:manage")
    public ApiResponse<SysNumberRule> disable(@PathVariable Long id) {
        return ApiResponse.ok(numberRuleService.toggleRule(id, false));
    }

    /** 生成下一个编号（**真实取号**，消费计数器；业务大批量取号走服务间调用） */
    @PostMapping("/next/{ruleKey}")
    @RequirePermission("number:manage")
    public ApiResponse<String> next(@PathVariable String ruleKey) {
        return ApiResponse.ok(numberRuleService.nextNumber(ruleKey));
    }

    /** 真预览（2026-11-28 审查实锤：原管理页"取号预览"走 POST /next，实测吃掉真实流水）——不落计数器。 */
    @GetMapping("/preview/{ruleKey}")
    @RequirePermission("number:manage")
    public ApiResponse<String> preview(@PathVariable String ruleKey) {
        return ApiResponse.ok(numberRuleService.previewNumber(ruleKey));
    }

    @Data
    public static class CreateRuleRequest {
        @NotBlank
        private String ruleKey;
        @NotBlank
        private String ruleName;
        private List<NumberRuleService.Segment> segments;
        private String resetPolicy;
    }
}
