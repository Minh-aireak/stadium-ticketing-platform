package com.aireak.inventory.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Inbound DTO for seat reservation REST call from booking-service. */
public record ReserveSeatsRequest(
        @NotBlank String bookingId,
        @NotEmpty
        @Size(max = SeatRequestLimits.MAX_SEATS_PER_REQUEST,
                message = "may not reserve more than {max} seats in one request")
        List<String> seatCodes
) {}
