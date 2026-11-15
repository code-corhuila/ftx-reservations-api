package com.futbolix.reservations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

    static final UUID COURT = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final LocalDate DATE = LocalDate.of(2030, 1, 10);
    static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");

    static Reservation pending() {
        TimeRange range = new TimeRange(LocalTime.of(18, 0), LocalTime.of(20, 0));
        BookableDay day = new BookableDay(COURT, DATE, "ACTIVE", 60_000, "COP", range.hourlySlots());
        return Reservation.pending(COURT, DATE, range, day,
                new Reservation.Customer("Test Customer", "customer@example.test", "3000000000"), NOW);
    }

    @Test
    void theAmountIsTheHourlyPriceTimesTheHours() {
        Reservation reservation = pending();

        assertThat(reservation.amount()).isEqualTo(120_000);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(ReservationCode.isWellFormed(reservation.code())).isTrue();
    }

    @Test
    void aPaymentApprovalConfirmsOnlyAPendingReservation() {
        Reservation reservation = pending();

        assertThat(reservation.confirm(NOW)).isTrue();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.confirm(NOW)).isFalse();
        assertThat(reservation.reject(NOW)).isFalse();
    }

    @Test
    void aCancellationKeepsWhoWhenAndWhy() {
        Reservation reservation = pending();

        reservation.cancel(CancelledBy.CUSTOMER, "  I can no longer attend ", NOW);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.cancelledBy()).isEqualTo(CancelledBy.CUSTOMER);
        assertThat(reservation.cancelledAt()).isEqualTo(NOW);
        assertThat(reservation.cancellationReason()).isEqualTo("I can no longer attend");
    }

    @Test
    void cancelledAndRejectedAreFinal() {
        Reservation cancelled = pending();
        cancelled.cancel(CancelledBy.ADMINISTRATOR, null, NOW);
        Reservation rejected = pending();
        rejected.reject(NOW);

        assertThatThrownBy(() -> cancelled.cancel(CancelledBy.CUSTOMER, null, NOW))
                .isInstanceOf(ReservationNotCancellableException.class);
        assertThatThrownBy(() -> rejected.cancel(CancelledBy.CUSTOMER, null, NOW))
                .isInstanceOf(ReservationNotCancellableException.class);
        assertThat(cancelled.confirm(NOW)).isFalse();
    }

    @Test
    void aCourtOffersARangeOnlyIfEveryHourIsASlot() {
        BookableDay day = new BookableDay(COURT, DATE, "ACTIVE", 60_000, "COP",
                List.of(new TimeRange(LocalTime.of(18, 0), LocalTime.of(19, 0))));

        assertThat(day.offers(new TimeRange(LocalTime.of(18, 0), LocalTime.of(19, 0)))).isTrue();
        assertThat(day.offers(new TimeRange(LocalTime.of(18, 0), LocalTime.of(20, 0)))).isFalse();
    }
}
