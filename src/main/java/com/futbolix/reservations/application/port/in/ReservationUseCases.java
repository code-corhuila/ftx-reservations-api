package com.futbolix.reservations.application.port.in;

import com.futbolix.reservations.domain.Reservation;
import java.time.LocalDate;
import java.util.UUID;

/** Everything the web and messaging adapters may ask of the reservations domain. */
public interface ReservationUseCases {

    Availability availability(UUID courtId, LocalDate date);

    Reservation create(CreateReservationCommand command);

    Reservation lookup(String code);

    Reservation cancelAsCustomer(String code, String reason);

    Page<Reservation> list(ReservationQuery query);

    Reservation cancelAsAdministrator(UUID id, String reason, String administratorId);

    /** @return true when the result changed the reservation, false when it was ignored. */
    boolean applyPaymentResult(PaymentResult result);
}
