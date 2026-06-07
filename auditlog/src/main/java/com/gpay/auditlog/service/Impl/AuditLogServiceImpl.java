package com.gpay.auditlog.service.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpay.auditlog.dto.AuditDTO.AuditLogRequest;
import com.gpay.auditlog.entity.AuditLog;
import com.gpay.auditlog.repository.AuditLogRepository;
import com.gpay.auditlog.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogServiceImpl implements AuditLogService {
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void save(AuditLogRequest request) {
        AuditLog log = AuditLog.builder()
                .traceId(request.traceId())
                .userId(request.userId())
                .transactionId(request.transactionId())
                .service(request.service())
                .action(request.action())
                .status(request.status())
                .requestPayload(toJson(request.requestPayload()))
                .responsePayload(toJson(request.responsePayload()))
                .errorMessage(request.errorMessage())
                .durationMs(request.durationMs())
                .ipAddress(request.ipAddress())
                .build();

        auditLogRepository.save(log);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
