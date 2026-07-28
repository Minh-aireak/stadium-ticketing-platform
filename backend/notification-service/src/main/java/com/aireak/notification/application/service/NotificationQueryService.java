package com.aireak.notification.application.service;

import com.aireak.notification.application.port.in.ListNotificationsUseCase;
import com.aireak.notification.application.port.in.MarkNotificationReadUseCase;
import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.domain.model.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Read-side + mark-as-read for in-app notifications — separate from the Kafka-driven dispatch/create path. */
@Service
@RequiredArgsConstructor
public class NotificationQueryService implements ListNotificationsUseCase, MarkNotificationReadUseCase {

    private final NotificationRepository notificationRepository;

    @Override
    public NotificationPage listByRecipient(String recipientId, int page, int size) {
        List<Notification> items = notificationRepository.findByRecipientId(recipientId, page, size);
        long total = notificationRepository.countByRecipientId(recipientId);
        return new NotificationPage(items, total, page, size);
    }

    @Override
    @Transactional
    public boolean markRead(String notificationId, String recipientId) {
        Optional<Notification> found = notificationRepository.findById(notificationId);
        if (found.isEmpty() || !found.get().getRecipientId().equals(recipientId)) {
            return false;
        }
        Notification notification = found.get();
        notification.markRead();
        notificationRepository.save(notification);
        return true;
    }
}
