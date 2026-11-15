package com.futbolix.reservations.application;

import com.futbolix.reservations.application.port.in.Availability;
import com.futbolix.reservations.application.port.in.CreateReservationCommand;
import com.futbolix.reservations.application.port.in.Page;
import com.futbolix.reservations.application.port.in.PaymentResult;
import com.futbolix.reservations.application.port.in.ReservationQuery;
import com.futbolix.reservations.application.port.in.ReservationUseCases;
import com.futbolix.reservations.application.port.out.BookableSlotsCatalog;
import com.futbolix.reservations.application.port.out.EventOutbox;
import com.futbolix.reservations.application.port.out.ReservationRepository;
import com.futbolix.reservations.application.port.out.ReservationRepository.CodeAlreadyUsedException;
import com.futbolix.reservations.application.port.out.ReservationRepository.IdempotencyKeyTakenException;
import com.futbolix.reservations.application.port.out.Transactions;
import com.futbolix.reservations.domain.BookableDay;
import com.futbolix.reservations.domain.BusinessRuleViolationException;
import com.futbolix.reservations.domain.CancelledBy;
import com.futbolix.reservations.domain.Reservation;
import com.futbolix.reservations.domain.ReservationCode;
import com.futbolix.reservations.domain.ReservationStatus;
import com.futbolix.reservations.domain.TimeRange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use cases of the reservations domain. No framework here: the adapters bring HTTP, JDBC and AMQP.
 * Logs carry ids and statuses only, never reservation codes or customer data (security-rules.md A09).
 */
public class ReservationService implements ReservationUseCases {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int CODE_ATTEMPTS = 3;

    private final ReservationRepository reservations;
    private final BookableSlotsCatalog catalog;
    private final EventOutbox outbox;
    private final Transactions transactions;
    private final Clock clock;   // business time zone: America/Bogota

    public ReservationService(ReservationRepository reservations, BookableSlotsCatalog catalog, EventOutbox outbox,
                              Transactions transactions, Clock clock) {
        this.reservations = reservations;
        this.catalog = catalog;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** FR-002 to FR-005. A UX aid only: the real guarantee is the exclusion constraint on write. */
    @Override
    public Availability availability(UUID courtId, LocalDate date) {
        BookableDay day = catalog.bookableDay(courtId, date);
        if (!day.courtIsActive()) {
            return new Availability(courtId, date, day.hourlyPrice(), day.currency(), List.of());
        }
        List<TimeRange> taken = reservations.activeOn(courtId, date).stream().map(Reservation::range).toList();
        LocalDateTime now = LocalDateTime.now(clock);
        List<Availability.Slot> slots = day.slots().stream()
                .map(slot -> new Availability.Slot(slot,
                        taken.stream().noneMatch(slot::overlaps) && !date.atTime(slot.start()).isBefore(now)))
                .toList();
        return new Availability(courtId, date, day.hourlyPrice(), day.currency(), slots);
    }

    /** FR-006 to FR-011 (HU-RES-002). */
    @Override
    public Reservation create(CreateReservationCommand command) {
        String requestHash = sha256(command.fingerprint());
        Optional<Reservation> original = replay(command.idempotencyKey(), requestHash);
        if (original.isPresent()) {
            return original.get();
        }

        TimeRange range = new TimeRange(command.startTime(), command.endTime());
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        if (command.date().atTime(range.start()).isBefore(now)) {
            throw new BusinessRuleViolationException("startTime", "The start time is in the past");
        }
        BookableDay day = catalog.bookableDay(command.courtId(), command.date());
        if (!day.courtIsActive()) {
            throw new BusinessRuleViolationException("courtId", "The court is not available for reservations");
        }
        if (!day.offers(range)) {
            throw new BusinessRuleViolationException("startTime", "The range is outside the court schedule");
        }

        Reservation reservation = Reservation.pending(command.courtId(), command.date(), range, day,
                new Reservation.Customer(command.customerName().strip(), command.customerEmail().strip(),
                        command.customerPhone().strip()),
                clock.instant());
        try {
            Reservation created = insertWithFreshCode(reservation, command.idempotencyKey(), requestHash);
            log.info("Reservation created id={} courtId={} date={} hours={} status={}", created.id(),
                    created.courtId(), created.date(), created.range().hours(), created.status());
            return created;
        } catch (SlotUnavailableException | IdempotencyKeyTakenException e) {
            // A concurrent request with the same Idempotency-Key may have won the race: return its result.
            return replay(command.idempotencyKey(), requestHash).orElseThrow(() -> {
                log.info("Reservation refused: slot taken courtId={} date={}", command.courtId(), command.date());
                return new SlotUnavailableException();
            });
        }
    }

    /** FR-016 (HU-RES-004). Malformed and unknown codes get the same 404. */
    @Override
    public Reservation lookup(String code) {
        if (!ReservationCode.isWellFormed(code)) {
            throw new ReservationNotFoundException();
        }
        return reservations.findByCode(code).orElseThrow(ReservationNotFoundException::new);
    }

    /** FR-012 to FR-015 (HU-RES-007). */
    @Override
    public Reservation cancelAsCustomer(String code, String reason) {
        if (!ReservationCode.isWellFormed(code)) {
            throw new ReservationNotFoundException();
        }
        return transactions.inTransaction(() -> cancel(
                reservations.lockByCode(code).orElseThrow(ReservationNotFoundException::new),
                CancelledBy.CUSTOMER, reason));
    }

    /** FR-033, FR-034. */
    @Override
    public Page<Reservation> list(ReservationQuery query) {
        return reservations.list(query);
    }

    /** FR-012, FR-013, FR-035. The administrator id goes to the audit log, not to the row. */
    @Override
    public Reservation cancelAsAdministrator(UUID id, String reason, String administratorId) {
        Reservation cancelled = transactions.inTransaction(() -> cancel(
                reservations.lockById(id).orElseThrow(ReservationNotFoundException::new),
                CancelledBy.ADMINISTRATOR, reason));
        log.info("audit action=reservation.cancel administratorId={} reservationId={}", administratorId, id);
        return cancelled;
    }

    /**
     * HU-RES-005. At-least-once delivery: the eventId is stored in the same transaction as the
     * status change, so a redelivered event changes nothing.
     */
    @Override
    public boolean applyPaymentResult(PaymentResult result) {
        return transactions.inTransaction(() -> {
            if (!reservations.markProcessed(result.eventId(), result.type().eventType())) {
                log.info("Payment event already processed eventId={}", result.eventId());
                return false;
            }
            Optional<Reservation> found = reservations.lockById(result.reservationId());
            if (found.isEmpty()) {
                log.warn("Payment event for an unknown reservation eventId={} reservationId={}",
                        result.eventId(), result.reservationId());
                return false;
            }
            Reservation reservation = found.get();
            ReservationStatus before = reservation.status();
            boolean changed = result.type() == PaymentResult.Type.PAYMENT_APPROVED
                    ? reservation.confirm(clock.instant())
                    : reservation.reject(clock.instant());
            if (changed) {
                reservations.updateStatus(reservation);
                log.info("Reservation {} -> {} id={} eventId={}", before, reservation.status(), reservation.id(),
                        result.eventId());
            } else {
                // ADR-005: approved after a cancellation means a charged customer; payment refunds it
                // when it consumes ReservationCancelled. Nothing to change here.
                log.warn("Payment event ignored: reservation is {} id={} eventId={} type={}", before,
                        reservation.id(), result.eventId(), result.type().eventType());
            }
            return changed;
        });
    }

    private Reservation cancel(Reservation reservation, CancelledBy by, String reason) {
        reservation.cancel(by, reason, clock.instant());
        reservations.updateStatus(reservation);
        outbox.reservationCancelled(reservation);
        log.info("Reservation cancelled id={} by={}", reservation.id(), by);
        return reservation;
    }

    /** The reservation, its idempotency key and ReservationCreated: one transaction or nothing. */
    private Reservation insertWithFreshCode(Reservation reservation, UUID idempotencyKey, String requestHash) {
        Reservation candidate = reservation;
        for (int attempt = 1; ; attempt++) {
            Reservation toInsert = candidate;
            try {
                return transactions.inTransaction(() -> {
                    reservations.insert(toInsert);
                    reservations.saveIdempotentRequest(idempotencyKey, requestHash, toInsert.id());
                    outbox.reservationCreated(toInsert);
                    return toInsert;
                });
            } catch (CodeAlreadyUsedException e) {
                if (attempt == CODE_ATTEMPTS) {
                    throw new IllegalStateException("Could not generate a unique reservation code", e);
                }
                candidate = candidate.withNewCode();
            }
        }
    }

    private Optional<Reservation> replay(UUID idempotencyKey, String requestHash) {
        return reservations.findIdempotentRequest(idempotencyKey).map(previous -> {
            if (!previous.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException();
            }
            return reservations.findById(previous.reservationId()).orElseThrow(ReservationNotFoundException::new);
        });
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
