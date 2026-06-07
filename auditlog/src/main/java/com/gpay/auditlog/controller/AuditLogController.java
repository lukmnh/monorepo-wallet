package com.gpay.auditlog.controller;

import com.gpay.auditlog.dto.AuditDTO;
import com.gpay.auditlog.dto.AuditDTO.ApiResponse;
import com.gpay.auditlog.service.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "api/v1/internal/audit")
@RequiredArgsConstructor
public class AuditLogController {
    private final AuditLogService auditLogService;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> save(@Valid @RequestBody AuditDTO.AuditLogRequest request) {
        auditLogService.save(request);
        return ResponseEntity.ok(ApiResponse.ok("Audit log saved", null));
    }
}
