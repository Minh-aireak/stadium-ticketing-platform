package com.aireak.notification.application.port.in;

public interface MarkNotificationReadUseCase {

    /**
     * @return true if a notification with this id exists and belongs to {@code recipientId}
     *         (and has now been marked read); false otherwise — the controller maps that to
     *         404 rather than distinguishing "not found" from "not yours", so a caller can't
     *         probe for other recipients' notification ids.
     */
    boolean markRead(String notificationId, String recipientId);
}
