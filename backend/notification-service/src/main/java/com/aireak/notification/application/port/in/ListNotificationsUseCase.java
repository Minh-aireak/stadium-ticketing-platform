package com.aireak.notification.application.port.in;

import com.aireak.notification.domain.model.Notification;

import java.util.List;

public interface ListNotificationsUseCase {

    NotificationPage listByRecipient(String recipientId, int page, int size);

    record NotificationPage(List<Notification> items, long totalElements, int page, int size) {}
}
