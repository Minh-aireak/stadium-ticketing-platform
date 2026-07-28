package com.aireak.notification.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single in-app notification for one recipient (distinct from the email/SMS side effects
 * {@link com.aireak.notification.application.service.NotificationDispatchService} also sends —
 * this is the record the "Notifications" screen in the frontend lists).
 */
public class Notification {

    private final String notificationId;
    private final String recipientId;
    private final String title;
    private final String body;
    private boolean read;
    private final Instant createdAt;

    private Notification(String notificationId, String recipientId, String title, String body,
                         boolean read, Instant createdAt) {
        this.notificationId = Objects.requireNonNull(notificationId);
        this.recipientId = Objects.requireNonNull(recipientId);
        this.title = Objects.requireNonNull(title);
        this.body = Objects.requireNonNull(body);
        this.read = read;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Notification create(String recipientId, String title, String body) {
        return new Notification(UUID.randomUUID().toString(), recipientId, title, body, false, Instant.now());
    }

    public static Notification reconstitute(String notificationId, String recipientId, String title,
                                            String body, boolean read, Instant createdAt) {
        return new Notification(notificationId, recipientId, title, body, read, createdAt);
    }

    public void markRead() {
        this.read = true;
    }

    public String getNotificationId() { return notificationId; }
    public String getRecipientId()    { return recipientId; }
    public String getTitle()          { return title; }
    public String getBody()           { return body; }
    public boolean isRead()           { return read; }
    public Instant getCreatedAt()     { return createdAt; }
}
