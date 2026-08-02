CREATE TABLE processed_inventory_events (
    event_id     VARCHAR(36) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
