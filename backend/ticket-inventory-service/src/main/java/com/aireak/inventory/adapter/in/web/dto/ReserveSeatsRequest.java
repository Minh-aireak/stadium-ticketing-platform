package com.aireak.inventory.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Inbound DTO for seat reservation REST call from booking-service. */
public record ReserveSeatsRequest(
        @NotBlank String bookingId,
        @NotEmpty List<String> seatCodes
) {}
