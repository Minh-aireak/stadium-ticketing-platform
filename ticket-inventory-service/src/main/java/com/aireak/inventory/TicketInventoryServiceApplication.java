package com.aireak.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ticket Inventory Service — Bounded Context: Inventory.
 * Manages seat availability and reservation for showtimes.
 * Distributed lock via Redisson; optimistic lock via JPA @Version.
 */
@SpringBootApplication
public class TicketInventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketInventoryServiceApplication.class, args);
    }
}
