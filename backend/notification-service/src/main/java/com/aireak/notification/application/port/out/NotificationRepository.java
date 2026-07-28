package com.aireak.notification.application.port.out;

import com.aireak.notification.domain.model.Notification;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository {
    void save(Notification notification);
    Optional<Notification> findById(String notificationId);

    /** Page of one recipient's notifications, newest first. */
    List<Notification> findByRecipientId(String recipientId, int page, int size);
    long countByRecipientId(String recipientId);
}
