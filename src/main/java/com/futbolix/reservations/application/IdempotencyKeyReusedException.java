package com.futbolix.reservations.application;

/** The same Idempotency-Key arrived with a different body: 422, nothing is created. */
public class IdempotencyKeyReusedException extends RuntimeException {
}
