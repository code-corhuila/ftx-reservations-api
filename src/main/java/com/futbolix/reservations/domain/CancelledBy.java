package com.futbolix.reservations.domain;

/** Who cancelled. Not an identity: a customer proves nothing but knowledge of the code. */
public enum CancelledBy {
    CUSTOMER, ADMINISTRATOR
}
