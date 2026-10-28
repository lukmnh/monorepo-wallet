package com.gpay.notification.controller;

import com.gpay.notification.dto.NotificationDTO.ApiResponse;
import com.gpay.notification.dto.NotificationDTO.IngestResponse;
import com.gpay.notification.dto.NotificationDTO.PaymentEvent;
import com.gpay.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Guarded by InternalApiKeyFilter (/api/v1/internal/**). */
@RestController
@RequestMapping("/api/v1/internal/notifications")
@RequiredArgsConstructor
public class InternalNotificationController {
    private final NotificationService notificationService;

    /** Always 200 for a valid event, including redeliveries, so the outbox can mark it published. */
    @PostMapping("/events")
    public ResponseEntity<ApiResponse<IngestResponse>> ingest(@Valid @RequestBody PaymentEvent event) {
        return ResponseEntity.ok(ApiResponse.ok("Event accepted", new IngestResponse(notificationService.ingest(event))));
    }
}
