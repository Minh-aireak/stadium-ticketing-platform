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
    // follows the event payload. The example V4 gives has expired -- a booking's seat list IS
    // capped now, at Booking.MAX_TICKETS and SeatRequestLimits.MAX_SEATS_PER_REQUEST, both 8, so
    // booking-confirmed renders well inside the VARCHAR(2000) V4 removed. What keeps the ceiling
    // off is booking-cancelled: it interpolates the cancellation reason, which reaches here from
    // an admin's free-text CancelMatchRequest and is bounded at no hop on the way. See
    // TransactionalEmailServiceTest, which pins both halves.
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "read", nullable = false)
    private boolean read;
}
