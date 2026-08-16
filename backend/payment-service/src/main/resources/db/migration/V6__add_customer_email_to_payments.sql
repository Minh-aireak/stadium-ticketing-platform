-- Recipient for the payment-success receipt email (notification-service consumes
-- PaymentSucceededEvent). Captured from the caller's validated JWT at initiation time, never from
-- the request body. Nullable: payments already in the table predate this column, and a payment
-- initiated by an internal-service token carries no end-user identity to record.
ALTER TABLE payments
    ADD COLUMN customer_email VARCHAR(255);
