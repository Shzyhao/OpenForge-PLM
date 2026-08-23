package com.openforge.doc.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.doc.dto.PageResponse;
import com.openforge.doc.entity.DocFile;
import com.openforge.doc.entity.DocInfo;
import com.openforge.doc.service.DocService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/docs")
@RequiredArgsConstructor
public class DocController {

    private final DocService docService;

    @PostMapping
    @RequirePermission("doc:create")
    public ApiResponse<DocInfo> create(@RequestBody CreateDocRequest request,
                                       HttpServletRequest httpRequest) {
        return ApiResponse.ok(docService.create(request.getTitle(), request.getDocType(),
                currentUserId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<PageResponse<DocInfo>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String docType) {
        return ApiResponse.ok(docService.page(page, pageSize, title, docType));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocInfo> detail(@PathVariable Long id) {
        return ApiResponse.ok(docService.detail(id));
    }

    @PostMapping("/{id}/check-out")
    @RequirePermission("doc:write")
    public ApiResponse<DocInfo> checkOut(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(docService.checkOut(id, currentUserId(request)));
    }

    @PostMapping("/{id}/check-in")
    @RequirePermission("doc:write")
    public ApiResponse<DocInfo> checkIn(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(docService.checkIn(id, currentUserId(request)));
    }

    @PostMapping("/{id}/files")
    @RequirePermission("doc:write")
    public ApiResponse<DocFile> upload(@PathVariable Long id,
                                       @RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(docService.uploadFile(id, file.getOriginalFilename(), file.getInputStream()));
    }

    @GetMapping("/{id}/files")
    public ApiResponse<List<DocFile>> files(@PathVariable Long id) {
        return ApiResponse.ok(docService.files(id));
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
    public static class CreateDocRequest {
        @NotBlank
        private String title;
        private String docType;
    }
}
