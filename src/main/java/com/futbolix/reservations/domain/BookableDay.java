package com.futbolix.reservations.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the schedules domain says about one court on one date: its status, its current price and
 * the one-hour slots it offers (ftx-schedules-api, GET /courts/{id}/bookable-slots).
 * Reservations are NOT subtracted here: that is this service's job.
 */
public record BookableDay(UUID courtId, LocalDate date, String courtStatus, long hourlyPrice, String currency,
                          List<TimeRange> slots) {

    public static final String ACTIVE = "ACTIVE";

    public BookableDay {
        slots = List.copyOf(slots);
    }

    public boolean courtIsActive() {
        return ACTIVE.equals(courtStatus);
    }

    /** Every hour of the range is a slot the court offers that day. */
    public boolean offers(TimeRange range) {
        return slots.containsAll(range.hourlySlots());
    }
}
