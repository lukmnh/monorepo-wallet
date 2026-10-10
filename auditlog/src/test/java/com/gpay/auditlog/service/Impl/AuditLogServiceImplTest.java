package com.gpay.auditlog.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpay.auditlog.dto.AuditDTO.AuditLogRequest;
import com.gpay.auditlog.entity.AuditLog;
import com.gpay.auditlog.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {
    @Mock AuditLogRepository repository;
    AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditLogServiceImpl(repository, new ObjectMapper());
    }

    private AuditLog saved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void mapsEveryFieldAndSerializesPayloadsAsJson() {
        UUID user = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        service.save(new AuditLogRequest("trace-1", user, txn, "payment-service", "TRANSFER", "SUCCESS",
                Map.of("amount", 10000), Map.of("ok", true), null, 42L, "1.1.1.1"));

        AuditLog log = saved();
        assertThat(log.getTraceId()).isEqualTo("trace-1");
        assertThat(log.getUserId()).isEqualTo(user);
        assertThat(log.getTransactionId()).isEqualTo(txn);
        assertThat(log.getAction()).isEqualTo("TRANSFER");
        assertThat(log.getRequestPayload()).isEqualTo("{\"amount\":10000}");
        assertThat(log.getResponsePayload()).isEqualTo("{\"ok\":true}");
        assertThat(log.getDurationMs()).isEqualTo(42L);
    }

    @Test
    void nullPayloadStaysNull() {
        service.save(new AuditLogRequest(null, null, null, "svc", "ACT", null, null, null, null, null, null));

        assertThat(saved().getRequestPayload()).isNull();
    }

    @Test
    void unserializablePayloadIsDroppedInsteadOfBreakingTheInsert() {
        // Jackson cannot serialize a bare Object (no properties) -> stored as null, not toString() garbage
        service.save(new AuditLogRequest(null, null, null, "svc", "ACT", null, new Object(), null, null, null, null));

        assertThat(saved().getRequestPayload()).isNull();
    }
}
