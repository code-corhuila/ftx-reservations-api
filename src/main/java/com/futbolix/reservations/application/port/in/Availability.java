package com.futbolix.reservations.application.port.in;

import com.futbolix.reservations.domain.TimeRange;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** GET /availability: every slot of the court on the date, free or taken (FR-002 to FR-005). */
public record Availability(UUID courtId, LocalDate date, long hourlyPrice, String currency, List<Slot> slots) {

    public record Slot(TimeRange range, boolean available) {
    }

    public boolean hasAvailability() {
        return slots.stream().anyMatch(Slot::available);
    }
}
