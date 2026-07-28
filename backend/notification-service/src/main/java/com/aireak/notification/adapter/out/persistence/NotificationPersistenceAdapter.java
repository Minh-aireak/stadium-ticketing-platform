package com.aireak.notification.adapter.out.persistence;

import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.domain.model.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class NotificationPersistenceAdapter implements NotificationRepository {

    private final NotificationJpaRepository jpaRepository;

    @Override
    public void save(Notification notification) {
        jpaRepository.save(toJpaEntity(notification));
    }

    @Override
    public Optional<Notification> findById(String notificationId) {
        return jpaRepository.findById(notificationId).map(this::toDomain);
    }

    @Override
    public List<Notification> findByRecipientId(String recipientId, int page, int size) {
        return jpaRepository
                .findByRecipientId(recipientId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toDomain)
                .getContent();
    }

    @Override
    public long countByRecipientId(String recipientId) {
        return jpaRepository.countByRecipientId(recipientId);
    }

    private NotificationJpaEntity toJpaEntity(Notification notification) {
        return NotificationJpaEntity.builder()
                .notificationId(notification.getNotificationId())
                .recipientId(notification.getRecipientId())
                .title(notification.getTitle())
                .body(notification.getBody())
                .read(notification.isRead())
                .build();
    }

    private Notification toDomain(NotificationJpaEntity entity) {
        return Notification.reconstitute(
                entity.getNotificationId(), entity.getRecipientId(), entity.getTitle(),
                entity.getBody(), entity.isRead(), entity.getCreatedAt());
    }
}
