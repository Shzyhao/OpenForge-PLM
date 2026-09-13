package com.openforge.drawing.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.drawing.dto.PageResponse;
import com.openforge.drawing.entity.DrawingFile;
import com.openforge.drawing.entity.DrawingInfo;
import com.openforge.drawing.entity.DrawingPart;
import com.openforge.drawing.entity.DrawingVersion;
import com.openforge.drawing.service.DrawingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/drawings")
@RequiredArgsConstructor
public class DrawingController {

    private final DrawingService drawingService;

    @PostMapping
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> create(@RequestBody CreateDrawingRequest request,
                                           HttpServletRequest httpRequest) {
        return ApiResponse.ok(drawingService.create(request.getTitle(), currentUserId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<PageResponse<DrawingInfo>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Long partId) {
        return ApiResponse.ok(drawingService.page(page, pageSize, title, state, partId));
    }

    @GetMapping("/{id}")
    public ApiResponse<DrawingInfo> detail(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.detail(id));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("drawing:manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        drawingService.delete(id);
        return ApiResponse.ok(null);
    }

    // ===== 文件 =====

    @PostMapping("/{id}/files")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingFile> upload(@PathVariable Long id,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam(defaultValue = "ATTACHMENT") String kind) throws Exception {
        return ApiResponse.ok(drawingService.uploadFile(id, file.getOriginalFilename(),
                file.getInputStream(), kind));
    }

    @GetMapping("/{id}/files")
    public ApiResponse<List<DrawingFile>> files(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.files(id));
    }

    /** 下载/预览源（流式；前端经 fetch+blob 内嵌预览 PDF/图片）。 */
    @GetMapping("/{id}/files/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id, @PathVariable Long fileId) {
        DrawingService.DownloadPayload payload = drawingService.download(id, fileId);
        String encoded = URLEncoder.encode(payload.file().getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(payload.stream()));
    }

    // ===== 检入检出与生命周期 =====

    @PostMapping("/{id}/check-out")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> checkOut(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(drawingService.checkOut(id, currentUserId(request)));
    }

    @PostMapping("/{id}/check-in")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> checkIn(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(drawingService.checkIn(id, currentUserId(request)));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> submit(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.submit(id));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> approve(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(drawingService.approve(id, currentUserId(request)));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> reject(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.reject(id));
    }

    @PostMapping("/{id}/obsolete")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> obsolete(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.obsolete(id));
    }

    @PostMapping("/{id}/revise")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingInfo> revise(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.revise(id));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<DrawingVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.versions(id));
    }

    // ===== 物料关联 =====

    @PostMapping("/{id}/parts")
    @RequirePermission("drawing:manage")
    public ApiResponse<DrawingPart> linkPart(@PathVariable Long id, @RequestBody LinkPartRequest request) {
        return ApiResponse.ok(drawingService.linkPart(id, request.getPartId(),
                request.getPartNumber(), request.getRole()));
    }

    @DeleteMapping("/{id}/parts/{partId}")
    @RequirePermission("drawing:manage")
    public ApiResponse<Void> unlinkPart(@PathVariable Long id, @PathVariable Long partId) {
        drawingService.unlinkPart(id, partId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/parts")
    public ApiResponse<List<DrawingPart>> parts(@PathVariable Long id) {
        return ApiResponse.ok(drawingService.parts(id));
    }

    /** 按物料反查图纸（物料详情/齐套检查入口）。 */
    @GetMapping("/by-part/{partId}")
    public ApiResponse<List<Map<String, Object>>> byPart(@PathVariable Long partId) {
        return ApiResponse.ok(drawingService.byPart(partId));
    }

    private Long currentUserId(HttpServletRequest request) {
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

    @Data
    public static class CreateDrawingRequest {
        @NotBlank
        private String title;
    }

    @Data
    public static class LinkPartRequest {
        private Long partId;
        private String partNumber;
        private String role;
    }
}
