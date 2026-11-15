package com.futbolix.reservations.application;

/** The range overlaps an active reservation (SQLSTATE 23P01): 409 RESERVATION_SLOT_UNAVAILABLE. */
public class SlotUnavailableException extends RuntimeException {
}
