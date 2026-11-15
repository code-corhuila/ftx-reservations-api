package com.futbolix.reservations.adapter.in.web;

import com.futbolix.reservations.domain.Reservation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Reservation schema of reservation-service.yaml. Times are HH:mm:ss. */
public record ReservationResponse(UUID id, String reservationCode, UUID courtId, LocalDate reservationDate,
                                  String startTime, String endTime, String status, MoneyDto amount,
                                  String customerName, String customerEmail, String customerPhone,
                                  Instant cancelledAt, String cancelledBy, String cancellationReason,
                                  Instant createdAt, Instant updatedAt) {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    static ReservationResponse from(Reservation r) {
        return new ReservationResponse(r.id(), r.code(), r.courtId(), r.date(), TIME.format(r.range().start()),
                TIME.format(r.range().end()), r.status().name(), new MoneyDto(r.amount(), r.currency()),
                r.customer().name(), r.customer().email(), r.customer().phone(), r.cancelledAt(),
                r.cancelledBy() == null ? null : r.cancelledBy().name(), r.cancellationReason(), r.createdAt(),
                r.updatedAt());
    }
}
