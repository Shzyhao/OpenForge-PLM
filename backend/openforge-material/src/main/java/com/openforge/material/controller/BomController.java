package com.openforge.material.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.material.dto.BomDiffResponse;
import com.openforge.material.dto.BomLineRequest;
import com.openforge.material.entity.Bom;
import com.openforge.material.entity.BomLine;
import com.openforge.material.service.BomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/boms")
@RequiredArgsConstructor
public class BomController {

    private final BomService bomService;

    @PostMapping
    @RequirePermission("bom:manage")
    public ApiResponse<Bom> create(@RequestBody Map<String, Long> body,
                                   jakarta.servlet.http.HttpServletRequest request) {
        Long operator = currentUserId(request);
        return ApiResponse.ok(bomService.create(body.get("parentPartId"), operator));
    }

    @PostMapping("/{id}/lines")
    @RequirePermission("bom:manage")
    public ApiResponse<BomLine> addLine(@PathVariable Long id,
                                        @Valid @RequestBody BomLineRequest lineRequest) {
        return ApiResponse.ok(bomService.addLine(id, lineRequest));
    }

    @GetMapping("/{id}/lines")
    public ApiResponse<List<BomLine>> lines(@PathVariable Long id) {
        return ApiResponse.ok(bomService.lines(id));
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @RequirePermission("bom:manage")
    public ApiResponse<Void> removeLine(@PathVariable Long id, @PathVariable Long lineId) {
        bomService.removeLine(id, lineId);
        return ApiResponse.ok();
    }

    @GetMapping("/expand")
    public ApiResponse<BomService.BomNode> expand(@RequestParam Long bomId,
                                                  @RequestParam(defaultValue = "10") int level) {
        return ApiResponse.ok(bomService.expand(bomId, level));
    }

    @GetMapping("/where-used")
    public ApiResponse<List<Map<String, Object>>> whereUsed(@RequestParam Long partId) {
        return ApiResponse.ok(bomService.whereUsed(partId));
    }

    @GetMapping("/compare")
    public ApiResponse<BomDiffResponse> compare(@RequestParam Long a, @RequestParam Long b) {
        return ApiResponse.ok(bomService.compare(a, b));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("bom:manage")
    public ApiResponse<Bom> submit(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        return ApiResponse.ok(bomService.submit(id, currentUserId(request)));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("bom:manage")
    public ApiResponse<Bom> approve(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        return ApiResponse.ok(bomService.approve(id, currentUserId(request)));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("bom:manage")
    public ApiResponse<Bom> reject(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        return ApiResponse.ok(bomService.reject(id, currentUserId(request)));
    }

    /** 网关信任头（security 模块约定一致） */
    private Long currentUserId(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
        if (header == null) {
            return null;
        }
        try {
            return Long.valueOf(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
