package com.futbolix.reservations.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The Reservation aggregate. Its data never changes after creation; only its status moves:
 *
 * <pre>
 * PENDING ──(PaymentApproved)──> CONFIRMED ──(cancel)──> CANCELLED
 *    ├──(PaymentRejected)──> REJECTED
 *    └──(cancel)──────────> CANCELLED
 * </pre>
 *
 * CONFIRMED and REJECTED are set only from a payment result (BR-008): no REST operation does it.
 * ftx-reservations-db enforces the same lifecycle with a trigger, so a bug here cannot break it.
 */
public final class Reservation {

    private final UUID id;
    private final String code;
    private final UUID courtId;
    private final LocalDate date;
    private final TimeRange range;
    private final long amount;
    private final String currency;
    private final Customer customer;
    private final Instant createdAt;
    private ReservationStatus status;
    private Instant updatedAt;
    private Instant cancelledAt;
    private CancelledBy cancelledBy;
    private String cancellationReason;

    public record Customer(String name, String email, String phone) {
    }

    public Reservation(UUID id, String code, UUID courtId, LocalDate date, TimeRange range, long amount,
                       String currency, Customer customer, ReservationStatus status, Instant createdAt,
                       Instant updatedAt, Instant cancelledAt, CancelledBy cancelledBy, String cancellationReason) {
        this.id = Objects.requireNonNull(id);
        this.code = Objects.requireNonNull(code);
        this.courtId = Objects.requireNonNull(courtId);
        this.date = Objects.requireNonNull(date);
        this.range = Objects.requireNonNull(range);
        this.amount = amount;
        this.currency = currency;
        this.customer = Objects.requireNonNull(customer);
        this.status = Objects.requireNonNull(status);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.cancellationReason = cancellationReason;
    }

    /** A new PENDING reservation. The amount is hourly price x hours, never a value from the client. */
    public static Reservation pending(UUID courtId, LocalDate date, TimeRange range, BookableDay day,
                                      Customer customer, Instant now) {
        long amount = Math.multiplyExact(day.hourlyPrice(), range.hours());
        if (amount <= 0) {
            throw new BusinessRuleViolationException("courtId", "The court has no valid price");
        }
        return new Reservation(UUID.randomUUID(), ReservationCode.generate(), courtId, date, range, amount,
                day.currency(), customer, ReservationStatus.PENDING, now, now, null, null, null);
    }

    /** Same reservation with another code, used only when the generated code collided. */
    public Reservation withNewCode() {
        return new Reservation(id, ReservationCode.generate(), courtId, date, range, amount, currency, customer,
                status, createdAt, updatedAt, cancelledAt, cancelledBy, cancellationReason);
    }

    public void cancel(CancelledBy by, String reason, Instant now) {
        if (!status.holdsSlot()) {
            throw new ReservationNotCancellableException();
        }
        status = ReservationStatus.CANCELLED;
        cancelledBy = Objects.requireNonNull(by);
        cancelledAt = now;
        cancellationReason = reason == null || reason.isBlank() ? null : reason.strip();
        updatedAt = now;
    }

    /** PaymentApproved. Only a PENDING reservation is confirmed; anything else is left as it is. */
    public boolean confirm(Instant now) {
        if (status != ReservationStatus.PENDING) {
            return false;
        }
        status = ReservationStatus.CONFIRMED;
        updatedAt = now;
        return true;
    }

    /** PaymentRejected. Only a PENDING reservation is rejected, which frees its slot. */
    public boolean reject(Instant now) {
        if (status != ReservationStatus.PENDING) {
            return false;
        }
        status = ReservationStatus.REJECTED;
        updatedAt = now;
        return true;
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public UUID courtId() { return courtId; }
    public LocalDate date() { return date; }
    public TimeRange range() { return range; }
    public long amount() { return amount; }
    public String currency() { return currency; }
    public Customer customer() { return customer; }
    public ReservationStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant cancelledAt() { return cancelledAt; }
    public CancelledBy cancelledBy() { return cancelledBy; }
    public String cancellationReason() { return cancellationReason; }
}
