package com.aireak.booking.application.port.out;

// Ambiguous outcome (timeout/reset/open circuit — no HTTP response at all), thrown by
// PaymentRestAdapter's fallback. Kept distinct from HttpStatusCodeException (downstream
// DID respond) so BookingOrchestrationService compensates immediately on that, but
// reconciles via PaymentPort#checkOutcome first on this one.
public class OutboundServiceUnavailableException extends RuntimeException {

    public OutboundServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
