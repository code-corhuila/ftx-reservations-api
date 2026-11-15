package com.futbolix.reservations.domain;

/** Reservation lifecycle (BR-006). PENDING and CONFIRMED hold the slot; the other two free it. */
public enum ReservationStatus {
    PENDING, CONFIRMED, CANCELLED, REJECTED;

    public boolean holdsSlot() {
        return this == PENDING || this == CONFIRMED;
    }
}
