package com.futbolix.reservations.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * CreateReservationRequest of reservation-service.yaml (additionalProperties: false, enforced by
 * fail-on-unknown-properties). There is no amount: the server computes it (FR-008).
 */
public record CreateReservationRequest(
        @NotNull UUID courtId,
        @NotNull LocalDate reservationDate,
        @NotNull @Pattern(regexp = TIME, message = "must be HH:mm:ss") String startTime,
        @NotNull @Pattern(regexp = TIME, message = "must be HH:mm:ss") String endTime,
        @NotBlank @Size(max = 150) String customerName,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @NotBlank @Size(min = 7, max = 30) @Pattern(regexp = "^\\+?[0-9 ]{7,30}$", message = "must be a phone number")
        String customerPhone) {

    static final String TIME = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$";
}
