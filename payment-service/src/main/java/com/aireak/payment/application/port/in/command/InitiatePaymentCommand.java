package com.aireak.payment.application.port.in.command;

import java.math.BigDecimal;

public record InitiatePaymentCommand(String bookingId, BigDecimal amount, String currency) {}
