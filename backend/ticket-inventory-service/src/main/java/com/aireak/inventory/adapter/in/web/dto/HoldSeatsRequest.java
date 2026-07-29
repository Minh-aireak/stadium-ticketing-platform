package com.aireak.inventory.adapter.in.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Inbound DTO for the standalone pre-booking hold REST call from the frontend. */
public record HoldSeatsRequest(@NotEmpty List<String> seatCodes) {}
