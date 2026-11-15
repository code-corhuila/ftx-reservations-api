package com.futbolix.reservations.adapter.in.web;

import jakarta.validation.constraints.Size;

/** Optional reason, kept for history (FR-035). */
public record CancelReservationRequest(@Size(max = 255) String reason) {
}
