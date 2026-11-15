package com.futbolix.reservations.adapter.in.web;

import com.futbolix.reservations.application.port.in.Availability;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Availability schema of reservation-service.yaml. */
public record AvailabilityResponse(UUID courtId, LocalDate date, MoneyDto hourlyPrice, boolean hasAvailability,
                                   List<SlotDto> slots) {

    public record SlotDto(String startTime, String endTime, boolean available) {
    }

    static AvailabilityResponse from(Availability a) {
        return new AvailabilityResponse(a.courtId(), a.date(), new MoneyDto(a.hourlyPrice(), a.currency()),
                a.hasAvailability(),
                a.slots().stream()
                        .map(s -> new SlotDto(ReservationResponse.TIME.format(s.range().start()),
                                ReservationResponse.TIME.format(s.range().end()), s.available()))
                        .toList());
    }
}
