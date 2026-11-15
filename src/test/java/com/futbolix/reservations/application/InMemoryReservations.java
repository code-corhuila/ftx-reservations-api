package com.futbolix.reservations.application;

import com.futbolix.reservations.application.port.in.Page;
import com.futbolix.reservations.application.port.in.ReservationQuery;
import com.futbolix.reservations.application.port.out.EventOutbox;
import com.futbolix.reservations.application.port.out.ReservationRepository;
import com.futbolix.reservations.domain.Reservation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository and outbox in memory for the use-case tests. insert() refuses an overlapping active
 * reservation, as the exclusion constraint does in the real database.
 */
class InMemoryReservations implements ReservationRepository, EventOutbox {

    final Map<UUID, Reservation> rows = new LinkedHashMap<>();
    final Map<UUID, IdempotentRequest> keys = new HashMap<>();
    final Set<UUID> processed = new HashSet<>();
    final List<String> events = new ArrayList<>();

    @Override
    public void insert(Reservation r) {
        boolean overlaps = rows.values().stream().anyMatch(other -> other.status().holdsSlot()
                && other.courtId().equals(r.courtId()) && other.date().equals(r.date())
                && other.range().overlaps(r.range()));
        if (overlaps) {
            throw new SlotUnavailableException();
        }
        rows.put(r.id(), r);
    }

    @Override
    public void updateStatus(Reservation r) {
        rows.put(r.id(), r);
    }

    @Override
    public Optional<Reservation> findByCode(String code) {
        return rows.values().stream().filter(r -> r.code().equals(code)).findFirst();
    }

    @Override
    public Optional<Reservation> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Reservation> lockByCode(String code) {
        return findByCode(code);
    }

    @Override
    public Optional<Reservation> lockById(UUID id) {
        return findById(id);
    }

    @Override
    public List<Reservation> activeOn(UUID courtId, LocalDate date) {
        return rows.values().stream()
                .filter(r -> r.courtId().equals(courtId) && r.date().equals(date) && r.status().holdsSlot())
                .toList();
    }

    @Override
    public Page<Reservation> list(ReservationQuery query) {
        List<Reservation> all = new ArrayList<>(rows.values());
        return new Page<>(all, query.page(), query.limit(), all.size());
    }

    @Override
    public Optional<IdempotentRequest> findIdempotentRequest(UUID key) {
        return Optional.ofNullable(keys.get(key));
    }

    @Override
    public void saveIdempotentRequest(UUID key, String requestHash, UUID reservationId) {
        if (keys.putIfAbsent(key, new IdempotentRequest(requestHash, reservationId)) != null) {
            throw new IdempotencyKeyTakenException();
        }
    }

    @Override
    public boolean markProcessed(UUID eventId, String eventType) {
        return processed.add(eventId);
    }

    @Override
    public void reservationCreated(Reservation reservation) {
        events.add("ReservationCreated:" + reservation.id());
    }

    @Override
    public void reservationCancelled(Reservation reservation) {
        events.add("ReservationCancelled:" + reservation.id() + ":" + reservation.cancelledBy());
    }
}
