package com.futbolix.reservations.application.port.in;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** POST /reservations, already parsed. No amount: the server computes it (FR-008). */
public record CreateReservationCommand(UUID idempotencyKey, UUID courtId, LocalDate date, LocalTime startTime,
                                       LocalTime endTime, String customerName, String customerEmail,
                                       String customerPhone) {

    /** Fingerprint of the body, to detect an Idempotency-Key reused with different data. */
    public String fingerprint() {
        return String.join("|", courtId.toString(), date.toString(), startTime.toString(), endTime.toString(),
                customerName, customerEmail.toLowerCase(), customerPhone);
    }
}
