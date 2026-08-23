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
    public ApiResponse<List<SysNumberRule>> list() {
        return ApiResponse.ok(numberRuleService.listRules());
    }

    @PostMapping("/rules")
    @RequirePermission("number:manage")
    public ApiResponse<SysNumberRule> create(@Valid @RequestBody CreateRuleRequest request) {
        return ApiResponse.ok(numberRuleService.createRule(
                request.getRuleKey(), request.getRuleName(),
                request.getSegments(), request.getResetPolicy()));
    }

    /** 生成下一个编号（预览/调试用；业务大批量取号走服务间调用） */
    @PostMapping("/next/{ruleKey}")
    @RequirePermission("number:manage")
    public ApiResponse<String> next(@PathVariable String ruleKey) {
        return ApiResponse.ok(numberRuleService.nextNumber(ruleKey));
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
