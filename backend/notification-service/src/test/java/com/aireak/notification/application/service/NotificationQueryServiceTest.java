package com.aireak.notification.application.service;

import com.aireak.notification.application.port.in.ListNotificationsUseCase;
import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.domain.model.Notification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    private static final String RECIPIENT_ID = "customer-1";

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationQueryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationQueryService(notificationRepository);
    }

    @Test
    void listByRecipientReturnsThePageAndTotalFromTheRepository() {
        Notification notification = Notification.create(RECIPIENT_ID, "Title", "Body");
        when(notificationRepository.findByRecipientId(RECIPIENT_ID, 0, 20)).thenReturn(List.of(notification));
        when(notificationRepository.countByRecipientId(RECIPIENT_ID)).thenReturn(1L);

        ListNotificationsUseCase.NotificationPage page = service.listByRecipient(RECIPIENT_ID, 0, 20);

        assertThat(page.items()).containsExactly(notification);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void markReadSavesTheNotificationMarkedReadWhenOwnedByTheCaller() {
        Notification notification = Notification.create(RECIPIENT_ID, "Title", "Body");
        when(notificationRepository.findById(notification.getNotificationId())).thenReturn(Optional.of(notification));

        boolean result = service.markRead(notification.getNotificationId(), RECIPIENT_ID);

        assertThat(result).isTrue();
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().isRead()).isTrue();
    }

    @Test
    void markReadReturnsFalseWhenNotFound() {
        when(notificationRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.markRead("missing", RECIPIENT_ID)).isFalse();
        verify(notificationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markReadReturnsFalseWhenOwnedBySomeoneElse() {
        Notification notification = Notification.create("someone-else", "Title", "Body");
        when(notificationRepository.findById(notification.getNotificationId())).thenReturn(Optional.of(notification));

        assertThat(service.markRead(notification.getNotificationId(), RECIPIENT_ID)).isFalse();
        verify(notificationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
