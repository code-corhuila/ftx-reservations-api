package com.futbolix.reservations.application.port.out;

import com.futbolix.reservations.domain.Reservation;

/**
 * Transactional outbox (ADR-006): the event is written in the same transaction as the reservation
 * change, and a relay publishes it later. If RabbitMQ is down, the reservation is still created.
 */
public interface EventOutbox {

    void reservationCreated(Reservation reservation);

    void reservationCancelled(Reservation reservation);
}
