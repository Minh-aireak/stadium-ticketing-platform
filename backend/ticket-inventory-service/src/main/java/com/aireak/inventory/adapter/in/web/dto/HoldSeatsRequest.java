package com.aireak.inventory.adapter.in.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Inbound DTO for the standalone pre-booking hold REST call from the frontend. */
public record HoldSeatsRequest(
        @NotEmpty
        @Size(max = SeatRequestLimits.MAX_SEATS_PER_REQUEST,
                message = "may not hold more than {max} seats in one request")
        List<String> seatCodes) {}
