package com.aireak.booking.adapter.in.web;

import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.application.port.in.GetBookingUseCase;
import com.aireak.booking.application.port.in.dto.BookingCreationResult;
import com.aireak.booking.application.service.DuplicateRequestInProgressException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final CreateBookingUseCase createBookingUseCase;
    private final GetBookingUseCase getBookingUseCase;

    @PostMapping
    public ResponseEntity<CreateBookingResponse> createBooking(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingCreationResult result = createBookingUseCase.createBooking(
                idempotencyKey,
                request.customerId(),
                request.showtimeId(),
                request.seatCodes(),
                request.amount(),
                request.currency()
        );
        // 201 = the booking resource now exists; it does NOT mean payment is confirmed —
        // callers must read `status`. PENDING_PAYMENT is the expected status even on a
        // fully successful call, since payment confirmation is always asynchronous
        // (PaymentResultConsumer) except for the rare ambiguous-but-reconciled case.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateBookingResponse(result.bookingId(), result.status().name()));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingStatusResponse> getBooking(@PathVariable("bookingId") String bookingId) {
        return getBookingUseCase.getBooking(bookingId)
                .map(b -> ResponseEntity.ok(new BookingStatusResponse(b.getBookingId(), b.getStatus().name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateBookingRequest(
            @NotBlank String customerId,
            @NotBlank String showtimeId,
            @NotEmpty List<String> seatCodes,
            @Positive BigDecimal amount,
            @NotBlank String currency
    ) {}

    public record CreateBookingResponse(String bookingId, String status) {}

    public record BookingStatusResponse(String bookingId, String status) {}

    // Handles duplicate in-flight requests with 409 Conflict
    @ExceptionHandler(DuplicateRequestInProgressException.class)
    public ProblemDetail handleDuplicateRequestInProgress(DuplicateRequestInProgressException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://aireak.com/errors/duplicate-request-in-progress"));
        problem.setTitle("Duplicate Request In Progress");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
