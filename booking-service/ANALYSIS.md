# Phân Tích Luồng Logic Booking Service

## Tổng Quan

Booking Service triển khai **Saga Pattern (Choreography + Orchestration)** để quản lý quy trình đặt vé. Service này đóng 
vai trò **Saga Orchestrator**, phối hợp các service khác (ticket-inventory, payment) để hoàn thành một booking.

Kiến trúc: **Hexagonal Architecture (Ports & Adapters)**

---

## 1. Cấu Trúc Package

```
booking-service/src/main/java/com/aireak/booking/
├── BookingServiceApplication.java          # Spring Boot entry point
├── domain/
│   ├── model/
│   │   ├── Booking.java                    # Aggregate Root (Entity)
│   │   ├── BookingStatus.java              # Enum: DRAFT, PENDING_PAYMENT, CONFIRMED, CANCELLED
│   │   ├── SeatSelection.java              # Value Object: danh sách ghế
│   │   └── BookingAmount.java              # Value Object: số tiền + currency
│   ├── event/
│   │   ├── BookingCreatedEvent.java        # Domain event
│   │   ├── BookingConfirmedEvent.java      # Domain event
│   │   └── BookingCancelledEvent.java      # Domain event
│   └── exception/
│       ├── InvalidBookingStatusException.java
│       └── MaxTicketsExceededException.java
├── application/
│   ├── service/
│   │   └── BookingOrchestrationService.java # Saga Orchestrator
│   └── port/out/
│       ├── BookingRepository.java           # Outbound port: persistence
│       ├── PaymentPort.java                 # Outbound port: payment REST
│       ├── TicketInventoryPort.java         # Outbound port: ticket-inventory REST
│       └── DomainEventPublisher.java        # Outbound port: Kafka publishing
├── adapter/
│   ├── in/
│   │   ├── web/
│   │   │   └── BookingController.java      # REST inbound adapter
│   │   └── messaging/
│   │       └── PaymentResultConsumer.java  # Kafka inbound adapter
│   └── out/
│       ├── client/
│       │   ├── PaymentRestAdapter.java     # REST outbound → payment-service
│       │   └── TicketInventoryRestAdapter.java # REST outbound → ticket-inventory-service
│       ├── messaging/
│       │   └── BookingEventPublisher.java  # Kafka outbound adapter
│       └── persistence/
│           ├── BookingJpaEntity.java       # JPA entity
│           ├── BookingJpaRepository.java   # Spring Data JPA
│           └── BookingPersistenceAdapter.java # Persistence adapter
└── config/
    ├── InfraConfig.java                    # RestClient beans
    └── KafkaConfig.java                    # Kafka configs
```

---

## 2. Luồng Chính: Tạo Booking (Happy Path)

### High-Level Sequence

```
CLIENT → REST Controller → OrchestrationService → ticket-inventory (REST)
                                                  → payment-service (REST)
                                                  → Kafka (domain events)
                                                  ↕ async callback (Kafka)
```

### Chi Tiết Từng Bước

#### Bước 0: REST Endpoint (BookingController)

```java
POST /api/v1/bookings
Body: { customerId, showtimeId, seatCodes, amount, currency }
→ BookingController.createBooking()
  → Gọi BookingOrchestrationService.createBooking(...)
  → Trả về 201 CREATED + { bookingId }
```

**Validation:** Jakarta Validation (`@NotBlank`, `@NotEmpty`, `@Positive`) trên các field.

#### Bước 1: Tạo Booking Aggregate (DRAFT)

**File:** `BookingOrchestrationService.createBooking()` (lines 54-96)

```java
// 1. Tạo SeatSelection VO (validate: không được null, không được rỗng)
SeatSelection seatSelection = new SeatSelection(seatCodes);

// 2. Tạo BookingAmount VO (validate: > 0, currency 3 ký tự)
BookingAmount bookingAmount = BookingAmount.of(amount, currency);

// 3. Factory method: tạo Booking với status = DRAFT
Booking booking = Booking.create(customerId, showtimeId, seatSelection, bookingAmount);
//   → Nếu seatSelection.count() > MAX_TICKETS (10) → throw MaxTicketsExceededException
//   → Sinh ra domain event: BookingCreatedEvent

// 4. Persist booking (JPA save)
bookingRepository.save(booking);
```

**Domain rules enforced:**
- Tối đa 10 vé mỗi booking
- Trạng thái khởi tạo: `DRAFT`

#### Bước 2: Reserve Seats (REST → ticket-inventory-service)

```java
try {
    ticketInventoryPort.reserveSeats(showtimeId, bookingId, seatCodes);
} catch (Exception e) {
    // COMPENSATING TRANSACTION: cancel booking
    booking.cancel("Seat reservation failed: " + e.getMessage());
    bookingRepository.save(booking);
    eventPublisher.publishAll(booking.pullDomainEvents()); // → BookingCancelledEvent
    throw e;
}
```

**Chi tiết REST call:**
- `TicketInventoryRestAdapter` gọi `POST /api/v1/inventory/{showtimeId}/reserve`
- Decorated với `@CircuitBreaker(name = "ticket-inventory")` + `@Retry(name = "ticket-inventory")`
- Fallback: `reserveSeatsFallback()` → throw `RuntimeException`
- Nếu fail → **compensate**: cancel booking (seats chưa từng được giữ, không cần release)

#### Bước 3: Chuyển sang PENDING_PAYMENT

```java
booking.markPendingPayment();
// → Yêu cầu status hiện tại phải là DRAFT, nếu không → InvalidBookingStatusException
bookingRepository.save(booking);
```

**State transition:** `DRAFT → PENDING_PAYMENT`

#### Bước 4: Initiate Payment (REST → payment-service)

```java
try {
    paymentPort.initiatePayment(bookingId, amount, currency);
} catch (Exception e) {
    // COMPENSATING TRANSACTION: release seats + cancel booking
    ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
    booking.cancel("Payment initiation failed: " + e.getMessage());
    bookingRepository.save(booking);
    eventPublisher.publishAll(booking.pullDomainEvents()); // → BookingCancelledEvent
    throw e;
}
```

**Chi tiết REST call:**
- `PaymentRestAdapter` gọi `POST /api/v1/payments`
- Decorated với `@CircuitBreaker(name = "payment")` + `@Retry(name = "payment")`
- Fallback: `initiatePaymentFallback()` → throw `RuntimeException`
- Nếu fail → **compensate**: releaseSeats (gọi ticket-inventory) + cancel booking

#### Bước 5: Publish Domain Events (Synchronous Success)

```java
eventPublisher.publishAll(booking.pullDomainEvents());
// → Lấy danh sách events đã tích lũy (BookingCreatedEvent)
// → Xóa khỏi aggregate (pullDomainEvents clear internal list)
// → Gửi lên Kafka topic: "booking.booking.created"
```

**Kafka mapping (BookingEventPublisher):**
| Domain Event | Kafka Topic |
|---|---|
| `BookingCreatedEvent` | `booking.booking.created` |
| `BookingConfirmedEvent` | `booking.booking.confirmed` |
| `BookingCancelledEvent` | `booking.booking.cancelled` |

---

## 3. Luồng Async: Callback từ Payment Service

Payment service xử lý payment **bất đồng bộ**, sau đó publish kết quả lên Kafka.

### 3.1 Payment Thành Công

```
payment-service → Kafka[payment.payment.succeeded]
                     ↓
              PaymentResultConsumer.consume()
                     ↓
              BookingOrchestrationService.confirmBooking(bookingId)
```

```java
public void confirmBooking(String bookingId) {
    // 1. Tìm booking trong DB
    Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> IllegalArgumentException("Booking not found"));
    
    // 2. State transition: PENDING_PAYMENT → CONFIRMED
    //    (nếu status không phải PENDING_PAYMENT → throw InvalidBookingStatusException)
    booking.confirm();
    
    // 3. Persist
    bookingRepository.save(booking);
    
    // 4. Publish BookingConfirmedEvent → topic "booking.booking.confirmed"
    eventPublisher.publishAll(booking.pullDomainEvents());
}
```

### 3.2 Payment Thất Bại

```
payment-service → Kafka[payment.payment.failed]
                     ↓
              PaymentResultConsumer.consume()
                     ↓
              BookingOrchestrationService.cancelBookingOnPaymentFailure(...)
```

```java
public void cancelBookingOnPaymentFailure(bookingId, showtimeId, seatCodes, reason) {
    // 1. Tìm booking
    Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(...);
    
    // 2. Compensate: release seats (REST → ticket-inventory-service)
    ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
    
    // 3. State transition: PENDING_PAYMENT → CANCELLED
    //    (nếu đã CONFIRMED hoặc CANCELLED → throw InvalidBookingStatusException)
    booking.cancel(reason);
    
    // 4. Persist
    bookingRepository.save(booking);
    
    // 5. Publish BookingCancelledEvent → topic "booking.booking.cancelled"
    eventPublisher.publishAll(booking.pullDomainEvents());
}
```

---

## 4. State Machine (Trạng thái Booking)

```
                    ┌────────────────────────────────────────────┐
                    │                                            │
                    ▼                                            │
              ┌──────────┐     reserveSeats success      ┌──────────────────┐
              │  DRAFT   │ ──────────────────────────▶  │ PENDING_PAYMENT  │
              │          │                              │                  │
              └────┬─────┘                              └────────┬─────────┘
                   │                                              │
                   │ reserveSeats FAIL                    ┌───────┴───────┐
                   │                                      │               │
                   ▼                              payment OK        payment FAIL
              ┌──────────┐                         │               │
              │ CANCELLED │◀────────────────────────┘               │
              │          │◀──────────────────────────────────────────┘
              └──────────┘     initiatePayment FAIL
                                      +
                              async payment FAIL
```

### Rules:
1. `DRAFT → PENDING_PAYMENT`: chỉ khi reserveSeats thành công
2. `PENDING_PAYMENT → CONFIRMED`: chỉ khi async payment thành công
3. `PENDING_PAYMENT → CANCELLED`: khi payment fail hoặc initiatePayment fail
4. `DRAFT → CANCELLED`: khi reserveSeats fail
5. `CONFIRMED` hoặc `CANCELLED` là **terminal states** — không thể chuyển tiếp

---

## 5. Compensating Transactions (Saga Pattern)

| Step | Service | Failure | Compensation |
|---|---|---|---|
| 1 | Booking.create() | Domain validation fail (max tickets) | Không có (exception trả về client) |
| 2 | reserveSeats(REST) | ticket-inventory unavailable/error | `booking.cancel()` (seats chưa được giữ) |
| 3 | markPendingPayment() | - | (chỉ là state transition trong memory) |
| 4 | initiatePayment(REST) | payment-service unavailable/error | `releaseSeats()` + `booking.cancel()` |
| 5 | publish events (Kafka) | Kafka unavailable | **Không có compensation** (fire-and-forget) |

### Notes:
- `releaseSeats()` fallback là **best-effort** — nếu ticket-inventory cũng down, log lỗi và swallow exception để không phá vỡ saga
- Step 4's compensation: releaseSeats được gọi **trước** khi cancel vì cancel có thể throw exception nếu status sai

---

## 6. Resilience & Error Handling

### CircuitBreaker + Retry
| REST Client | CircuitBreaker Name | Fallback Behavior |
|---|---|---|
| `TicketInventoryRestAdapter` | `ticket-inventory` | `reserveSeatsFallback()` → throw RuntimeException<br>`releaseSeatsFallback()` → log + swallow |
| `PaymentRestAdapter` | `payment` | `initiatePaymentFallback()` → throw RuntimeException |

### Domain Exceptions
| Exception | Khi nào xảy ra |
|---|---|
| `MaxTicketsExceededException` | `Booking.create()` nếu > 10 ghế |
| `InvalidBookingStatusException` | Gọi `markPendingPayment()`/`confirm()`/`cancel()` sai trạng thái |

---

## 7. Domain Events & Kafka Topics

| Event | Source | Kafka Topic | Consumer |
|---|---|---|---|
| `BookingCreatedEvent` | Booking.create() → publish ở Step 5 | `booking.booking.created` | notification-service? |
| `BookingConfirmedEvent` | Booking.confirm() sau payment success | `booking.booking.confirmed` | notification-service |
| `BookingCancelledEvent` | Booking.cancel() (3 scenarios) | `booking.booking.cancelled` | notification-service |
| PAYMENT_SUCCEEDED | payment-service (external) | `payment.payment.succeeded` | **booking-service** (PaymentResultConsumer) |
| PAYMENT_FAILED | payment-service (external) | `payment.payment.failed` | **booking-service** (PaymentResultConsumer) |

---

## 8. Idempotency

**Note trong `PaymentResultConsumer`**:
> Idempotency is handled at the orchestration service level via booking status check
> (PENDING_PAYMENT → CONFIRMED/CANCELLED — if already terminal, no-op)

Tuy nhiên, code hiện tại chưa có xử lý explicit cho idempotency:
- `booking.confirm()` yêu cầu status phải là `PENDING_PAYMENT` — nếu nhận duplicate message và booking đã `CONFIRMED` rồi, sẽ throw InvalidBookingStatusException
- Đây là một **issue tiềm năng** — có thể cần try-catch hoặc kiểm tra status trước

---

## 9. Persistence Details

- Table: `bookings`
- Seat codes lưu dưới dạng **comma-separated string** ("A1,A2,B3")
- Có `@Version` field cho optimistic locking
- `BaseAuditEntity` chứa các audit fields (created_by, modified_at, etc.)

---

## 10. Điểm Yếu & Lưu Ý

1. **Idempotency chưa hoàn thiện**: Nếu Kafka consumer nhận duplicate message, booking.confirm() hoặc booking.cancel() sẽ throw exception thay vì no-op
2. **Kafka publishing không transactional**: Nếu Step 5 (publish events) fail sau khi commit DB transaction, booking sẽ ở PENDING_PAYMENT nhưng event chưa được gửi — có thể dẫn đến booking "treo"
3. **releaseSeats fallback swallow exception**: Có thể dẫn đến mất consistency nếu ticket-inventory thực sự không thể release nhưng booking vẫn được cancel
4. **Không có timeout/scheduler**: Không có retry mechanism cho booking ở PENDING_PAYMENT quá lâu (payment-service không trả về kết quả)
5. **seatCodes lưu comma-separated**: Không lý tưởng cho query, có thể dùng `@ElementCollection` với bảng riêng cho production