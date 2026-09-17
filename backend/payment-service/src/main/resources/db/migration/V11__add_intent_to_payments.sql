-- Card mode (payment.mode=card): the charge is no longer created and confirmed in one server-side
-- call. payment-service creates a PaymentIntent up front and the customer's browser confirms it
-- with a card they typed, so the intent's id has to be on the row from the start -- it is what the
-- expiry job cancels when the payment window closes and what POST /payments/{bookingId}/sync
-- retrieves to learn the outcome without waiting for a webhook. client_secret is the half of the
-- intent Stripe.js needs to confirm it; it is handed only to the booking's owner.
--
-- Both stay NULL in auto mode, and a NULL client_secret is how the code tells the two modes apart
-- (Payment#isCardMode).
ALTER TABLE payments
    ADD COLUMN gateway_intent_id VARCHAR(100),
    ADD COLUMN client_secret VARCHAR(200);
