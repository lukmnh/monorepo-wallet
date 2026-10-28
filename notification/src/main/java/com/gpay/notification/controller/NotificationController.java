package com.gpay.notification.controller;

import com.gpay.notification.dto.NotificationDTO.ApiResponse;
import com.gpay.notification.dto.NotificationDTO.MarkAllReadResponse;
import com.gpay.notification.dto.NotificationDTO.NotificationResponse;
import com.gpay.notification.dto.NotificationDTO.PageResponse;
import com.gpay.notification.dto.NotificationDTO.UnreadCountResponse;
import com.gpay.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> list(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Clamp instead of failing: PageRequest.of throws on page < 0 or size < 1
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ResponseEntity.ok(ApiResponse.ok("Notifications retrieved",
                notificationService.list(userId(auth), safePage, safeSize)));
    }

    /** Lightweight badge endpoint: clients poll this instead of the full list. */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok("Unread count retrieved",
                new UnreadCountResponse(notificationService.unreadCount(userId(auth)))));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(Authentication auth, @PathVariable UUID id) {
        notificationService.markRead(userId(auth), id);
        return ResponseEntity.ok(ApiResponse.ok("Notification marked as read", null));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<MarkAllReadResponse>> markAllRead(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok("All notifications marked as read",
                new MarkAllReadResponse(notificationService.markAllRead(userId(auth)))));
    }

    private static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
