package com.aireak.payment.application.port.in;

import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;

/**
 * Inbound port: initiate a payment transaction.
 * Called synchronously by booking-service via REST.
 */
public interface InitiatePaymentUseCase {
    /** @return paymentId */
    String execute(InitiatePaymentCommand command);
}
