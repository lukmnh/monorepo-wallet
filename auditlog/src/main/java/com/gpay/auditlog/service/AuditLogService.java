package com.gpay.auditlog.service;

import com.gpay.auditlog.dto.AuditDTO.AuditLogRequest;

public interface AuditLogService {
    void save(AuditLogRequest request);
}
