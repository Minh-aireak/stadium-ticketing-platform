package com.aireak.payment.application.port.in.command;

import java.math.BigDecimal;

/**
 * {@code customerEmail} is the caller's address as it appears in their validated JWT — never a
 * client-supplied body field — and is null for internal-service calls that have no end-user
 * identity behind them. It is stored on the Payment purely so the receipt email has a recipient.
 */
public record InitiatePaymentCommand(String bookingId, String customerEmail, BigDecimal amount, String currency) {}
