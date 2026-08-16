package com.aireak.notification.adapter.out.persistence;

import com.aireak.common.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "notifications")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "notification_id", nullable = false, length = 36)
    private String notificationId;

    @Column(name = "recipient_id", nullable = false, length = 36)
    private String recipientId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    // TEXT, not a bounded VARCHAR: this holds the rendered plain-text email body, whose length
    // follows the event payload (a booking's seat list is not capped anywhere). See V4.
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "read", nullable = false)
    private boolean read;
}
