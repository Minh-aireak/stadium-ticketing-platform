package com.aireak.booking.domain.model;

import java.util.List;

/**
 * What a customer's cancel request amounts to against a booking's current state — decided by
 * {@link Booking#planCustomerCancellation} without touching anything, so the caller can do the
 * slow reads a paid cancellation needs (the kickoff time, the seat prices) before taking the lock
 * and opening the transaction that applies it.
 */
public sealed interface CustomerCancellation {

    /** The seats this plan is about. */
    List<String> seatCodes();

    /** Every requested seat is already cancelled — a repeat of a request that already went through. */
    record AlreadyCancelled(List<String> seatCodes) implements CustomerCancellation {
        public AlreadyCancelled {
            seatCodes = List.copyOf(seatCodes);
        }
    }

    /** An unpaid booking, cancelled as a whole: nothing to refund, its seat holds to drop. */
    record Unpaid(List<String> seatCodes) implements CustomerCancellation {
        public Unpaid {
            seatCodes = List.copyOf(seatCodes);
        }
    }

    /** Paid seats: they go back on sale and their price goes back to the customer. */
    record Paid(List<String> seatCodes) implements CustomerCancellation {
        public Paid {
            seatCodes = List.copyOf(seatCodes);
        }
    }
}
