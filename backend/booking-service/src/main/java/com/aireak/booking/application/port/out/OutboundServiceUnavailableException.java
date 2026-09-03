package com.aireak.booking.application.port.out;

// A downstream service could not be reached, or could not be made to answer usefully: timeout,
// reset, open circuit, full bulkhead, or a 5xx that outlived the retries. Raised by both outbound
// adapters' fallbacks — PaymentRestAdapter's and TicketInventoryRestAdapter's.
//
// Kept distinct from HttpStatusCodeException (downstream DID respond, about THIS request) for two
// separate reasons:
//   - BookingOrchestrationService's Step 4 compensates immediately on an HttpStatusCodeException
//     but reconciles via PaymentPort#checkOutcome first on this one, since the charge may already
//     be running. Step 2 does not branch: it compensates on anything.
//   - BookingController maps it to 503. That is the whole reason it is a named type rather than
//     the bare RuntimeException TicketInventoryRestAdapter used to throw: a customer whose
//     booking failed because a dependency was down was being told the platform had broken.
public class OutboundServiceUnavailableException extends RuntimeException {

    public OutboundServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
