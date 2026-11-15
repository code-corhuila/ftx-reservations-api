package com.futbolix.reservations.domain;

/** The reservation is already CANCELLED or REJECTED: 409 RESERVATION_NOT_CANCELLABLE. */
public class ReservationNotCancellableException extends RuntimeException {

    public ReservationNotCancellableException() {
        super("The reservation can no longer be cancelled");
    }
}
