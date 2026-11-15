package com.futbolix.reservations.application.port.out;

import com.futbolix.reservations.application.port.in.Page;
import com.futbolix.reservations.application.port.in.ReservationQuery;
import com.futbolix.reservations.domain.Reservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** reservations.reservation and its supporting tables (ftx-reservations-db). */
public interface ReservationRepository {

    /**
     * @throws com.futbolix.reservations.application.SlotUnavailableException overlap with an active
     *         reservation, decided by the database exclusion constraint
     * @throws CodeAlreadyUsedException the random code collided (practically never): retry
     */
    void insert(Reservation reservation);

    void updateStatus(Reservation reservation);

    Optional<Reservation> findByCode(String code);

    Optional<Reservation> findById(UUID id);

    /** Same as findByCode, locking the row until the transaction ends (SELECT ... FOR UPDATE). */
    Optional<Reservation> lockByCode(String code);

    /** Same as findById, locking the row until the transaction ends (SELECT ... FOR UPDATE). */
    Optional<Reservation> lockById(UUID id);

    /** Reservations that hold a slot (PENDING, CONFIRMED) on that court and date. */
    List<Reservation> activeOn(UUID courtId, LocalDate date);

    Page<Reservation> list(ReservationQuery query);

    Optional<IdempotentRequest> findIdempotentRequest(UUID key);

    /** @throws IdempotencyKeyTakenException a concurrent request with the same key committed first */
    void saveIdempotentRequest(UUID key, String requestHash, UUID reservationId);

    /** Stores the eventId; false when it was already processed (redelivery). */
    boolean markProcessed(UUID eventId, String eventType);

    record IdempotentRequest(String requestHash, UUID reservationId) {
    }

    class CodeAlreadyUsedException extends RuntimeException {
    }

    class IdempotencyKeyTakenException extends RuntimeException {
    }
}
