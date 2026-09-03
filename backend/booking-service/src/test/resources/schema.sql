-- H2 schema for BookingJpaAuditingH2Test only. Spring Boot auto-runs this against the embedded
-- H2 datasource that @DataJpaTest substitutes in (spring.sql.init.mode defaults to "embedded").
-- Kept minimal (bookings table only) and Hibernate schema management is disabled
-- (spring.jpa.hibernate.ddl-auto=none in that test) so this never runs against a real
-- datasource — other tests in this module either don't use an embedded DB or replace it with a
-- real Postgres container (@AutoConfigureTestDatabase(replace = NONE)), which this doesn't touch.
CREATE TABLE bookings (
    booking_id VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL,
    customer_email VARCHAR(255),
    showtime_id VARCHAR(36) NOT NULL,
    seat_codes VARCHAR(1000) NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(255),
    inventory_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    inventory_sale_refused BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
