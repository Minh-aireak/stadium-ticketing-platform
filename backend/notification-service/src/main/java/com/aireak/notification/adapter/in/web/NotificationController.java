package com.aireak.notification.adapter.in.web;

import com.aireak.notification.application.port.in.ListNotificationsUseCase;
import com.aireak.notification.application.port.in.MarkNotificationReadUseCase;
import com.aireak.notification.domain.model.Notification;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** Inbound REST adapter: "my notifications" — always scoped to the JWT-authenticated caller. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ListNotificationsUseCase listNotificationsUseCase;
    private final MarkNotificationReadUseCase markNotificationReadUseCase;

    @GetMapping
    public ResponseEntity<NotificationListResponse> listMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);

        String recipientId = currentUser().userId();
        ListNotificationsUseCase.NotificationPage result =
                listNotificationsUseCase.listByRecipient(recipientId, safePage, safeSize);
        return ResponseEntity.ok(toListResponse(result));
    }

    /** Marks one of the caller's own notifications read; 404 if it doesn't exist or isn't theirs. */
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@PathVariable("notificationId") String notificationId) {
        boolean updated = markNotificationReadUseCase.markRead(notificationId, currentUser().userId());
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    private AuthenticatedUser currentUser() {
        return AuthenticatedUserContext.get()
                .orElseThrow(() -> new IllegalStateException("JwtAuthenticationFilter did not run for this request"));
    }

    private NotificationListResponse toListResponse(ListNotificationsUseCase.NotificationPage result) {
        return new NotificationListResponse(
                result.items().stream().map(this::toSummary).toList(),
                result.totalElements(), result.page(), result.size());
    }

    private NotificationSummaryResponse toSummary(Notification notification) {
        return new NotificationSummaryResponse(
                notification.getNotificationId(), notification.getTitle(), notification.getBody(),
                notification.isRead(), notification.getCreatedAt());
    }

    public record NotificationSummaryResponse(String notificationId, String title, String body,
                                              boolean read, Instant createdAt) {}

    public record NotificationListResponse(List<NotificationSummaryResponse> items,
                                           long totalElements, int page, int size) {}
}
