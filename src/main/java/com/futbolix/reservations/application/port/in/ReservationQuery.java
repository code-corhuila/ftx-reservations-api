package com.futbolix.reservations.application.port.in;

import com.futbolix.reservations.domain.ReservationStatus;
import java.time.LocalDate;
import java.util.UUID;

/** Filters of GET /admin/reservations. Every filter is optional. */
public record ReservationQuery(int page, int limit, ReservationStatus status, UUID courtId, LocalDate dateFrom,
                               LocalDate dateTo) {

    public int offset() {
        return (page - 1) * limit;
    }
}
