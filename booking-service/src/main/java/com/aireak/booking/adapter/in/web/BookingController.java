package com.aireak.booking.adapter.in.web;

import com.aireak.booking.application.service.BookingOrchestrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Inbound REST adapter for booking creation. */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingOrchestrationService bookingOrchestrationService;

    @PostMapping
    public ResponseEntity<CreateBookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest request) {
        String bookingId = bookingOrchestrationService.createBooking(
                request.customerId(),
                request.showtimeId(),
                request.seatCodes(),
                request.amount(),
                request.currency()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateBookingResponse(bookingId));
    }

    record CreateBookingRequest(
            @NotBlank String customerId,
            @NotBlank String showtimeId,
            @NotEmpty List<String> seatCodes,
            @Positive BigDecimal amount,
            @NotBlank String currency
    ) {}

    record CreateBookingResponse(String bookingId) {}
}
