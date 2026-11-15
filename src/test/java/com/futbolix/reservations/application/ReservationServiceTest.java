package com.futbolix.reservations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.futbolix.reservations.application.port.in.Availability;
import com.futbolix.reservations.application.port.in.CreateReservationCommand;
import com.futbolix.reservations.application.port.in.PaymentResult;
import com.futbolix.reservations.application.port.out.BookableSlotsCatalog;
import com.futbolix.reservations.application.port.out.Transactions;
import com.futbolix.reservations.domain.BookableDay;
import com.futbolix.reservations.domain.BusinessRuleViolationException;
import com.futbolix.reservations.domain.CancelledBy;
import com.futbolix.reservations.domain.Reservation;
import com.futbolix.reservations.domain.ReservationNotCancellableException;
import com.futbolix.reservations.domain.ReservationStatus;
import com.futbolix.reservations.domain.TimeRange;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReservationServiceTest {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    static final UUID COURT = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final LocalDate TODAY = LocalDate.of(2030, 1, 10);
    static final LocalDate TOMORROW = TODAY.plusDays(1);

    InMemoryReservations store;
    String courtStatus;
    boolean schedulesDown;
    ReservationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryReservations();
        courtStatus = "ACTIVE";
        schedulesDown = false;
        // Business time: 2030-01-10 15:20 in Bogota.
        Clock clock = Clock.fixed(ZonedDateTime.of(TODAY, LocalTime.of(15, 20), BOGOTA).toInstant(), BOGOTA);
        BookableSlotsCatalog catalog = (courtId, date) -> {
            if (schedulesDown) {
                throw new DependencyUnavailableException("ftx-schedules-api", null);
            }
            if (!courtId.equals(COURT)) {
                throw new CourtNotFoundException();
            }
            // Open 06:00-22:00, 60 000 COP per hour.
            return new BookableDay(courtId, date, courtStatus, 60_000, "COP",
                    new TimeRange(LocalTime.of(6, 0), LocalTime.of(22, 0)).hourlySlots());
        };
        Transactions direct = new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }
        };
        service = new ReservationService(store, catalog, store, direct, clock);
    }

    static CreateReservationCommand command(UUID key, LocalDate date, int from, int to) {
        return new CreateReservationCommand(key, COURT, date, LocalTime.of(from, 0), LocalTime.of(to, 0),
                "Test Customer", "customer@example.test", "3000000000");
    }

    @Test
    void createsAPendingReservationWithTheServerSideAmountAndItsEvent() {
        Reservation r = service.create(command(UUID.randomUUID(), TOMORROW, 18, 20));

        assertThat(r.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(r.amount()).isEqualTo(120_000);
        assertThat(store.events).containsExactly("ReservationCreated:" + r.id());
    }

    @Test
    void theSameIdempotencyKeyReturnsTheOriginalReservation() {
        UUID key = UUID.randomUUID();
        Reservation first = service.create(command(key, TOMORROW, 18, 20));
        Reservation again = service.create(command(key, TOMORROW, 18, 20));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(store.rows).hasSize(1);
        assertThat(store.events).hasSize(1);
    }

    @Test
    void theSameIdempotencyKeyWithAnotherBodyIsRefused() {
        UUID key = UUID.randomUUID();
        service.create(command(key, TOMORROW, 18, 20));

        assertThatThrownBy(() -> service.create(command(key, TOMORROW, 9, 10)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void anOverlappingRangeIsRefusedWith409() {
        service.create(command(UUID.randomUUID(), TOMORROW, 18, 20));

        assertThatThrownBy(() -> service.create(command(UUID.randomUUID(), TOMORROW, 19, 21)))
                .isInstanceOf(SlotUnavailableException.class);
    }

    @Test
    void aStartTimeInThePastIsRefused() {
        assertThatThrownBy(() -> service.create(command(UUID.randomUUID(), TODAY, 15, 16)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("past");
        // 16:00 today is still in the future at 15:20.
        assertThat(service.create(command(UUID.randomUUID(), TODAY, 16, 17)).status()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    void aRangeOutsideTheScheduleOrOnAnInactiveCourtIsRefused() {
        assertThatThrownBy(() -> service.create(command(UUID.randomUUID(), TOMORROW, 21, 23)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("schedule");

        courtStatus = "UNDER_MAINTENANCE";
        assertThatThrownBy(() -> service.create(command(UUID.randomUUID(), TOMORROW, 18, 19)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("field").isEqualTo("courtId");
    }

    @Test
    void anUnknownCourtAndASilentSchedulesServiceAreNotGuessed() {
        CreateReservationCommand unknownCourt = new CreateReservationCommand(UUID.randomUUID(), UUID.randomUUID(),
                TOMORROW, LocalTime.of(18, 0), LocalTime.of(19, 0), "Test", "t@example.test", "3000000000");
        assertThatThrownBy(() -> service.create(unknownCourt)).isInstanceOf(CourtNotFoundException.class);

        schedulesDown = true;
        assertThatThrownBy(() -> service.availability(COURT, TOMORROW))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void availabilitySubtractsActiveReservationsAndPastHours() {
        service.create(command(UUID.randomUUID(), TODAY, 18, 20));

        Availability availability = service.availability(COURT, TODAY);

        assertThat(availability.hourlyPrice()).isEqualTo(60_000);
        assertThat(availability.slots()).hasSize(16);
        assertThat(slot(availability, 15).available()).isFalse();   // 15:00 already started
        assertThat(slot(availability, 16).available()).isTrue();
        assertThat(slot(availability, 18).available()).isFalse();   // reserved
        assertThat(slot(availability, 19).available()).isFalse();   // reserved
        assertThat(slot(availability, 20).available()).isTrue();
        assertThat(availability.hasAvailability()).isTrue();
    }

    @Test
    void anInactiveCourtHasNoSlots() {
        courtStatus = "INACTIVE";

        Availability availability = service.availability(COURT, TOMORROW);

        assertThat(availability.slots()).isEmpty();
        assertThat(availability.hasAvailability()).isFalse();
    }

    @Test
    void aCancelledReservationFreesItsSlotAndPublishesTheEvent() {
        Reservation r = service.create(command(UUID.randomUUID(), TOMORROW, 18, 20));

        Reservation cancelled = service.cancelAsCustomer(r.code(), "I can no longer attend");

        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(store.events).contains("ReservationCancelled:" + r.id() + ":CUSTOMER");
        assertThat(service.create(command(UUID.randomUUID(), TOMORROW, 18, 20)).status())
                .isEqualTo(ReservationStatus.PENDING);
        assertThatThrownBy(() -> service.cancelAsCustomer(r.code(), null))
                .isInstanceOf(ReservationNotCancellableException.class);
    }

    @Test
    void unknownAndMalformedCodesGetTheSameNotFound() {
        assertThatThrownBy(() -> service.lookup("ZZZZZZZZZZ")).isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.lookup("'; DROP TABLE")).isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.cancelAsCustomer("bad-code", null)).isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void anAdministratorCancelsById() {
        Reservation r = service.create(command(UUID.randomUUID(), TOMORROW, 18, 20));

        Reservation cancelled = service.cancelAsAdministrator(r.id(), "Maintenance", "admin-1");

        assertThat(cancelled.cancelledBy()).isEqualTo(CancelledBy.ADMINISTRATOR);
        assertThatThrownBy(() -> service.cancelAsAdministrator(UUID.randomUUID(), null, "admin-1"))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void onlyAPaymentResultConfirmsOrRejects_andARedeliveryChangesNothing() {
        Reservation r = service.create(command(UUID.randomUUID(), TOMORROW, 18, 20));
        UUID eventId = UUID.randomUUID();

        assertThat(service.applyPaymentResult(new PaymentResult(eventId, PaymentResult.Type.PAYMENT_APPROVED, r.id()))).isTrue();
        assertThat(service.lookup(r.code()).status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Same eventId again (at-least-once delivery): ignored.
        assertThat(service.applyPaymentResult(new PaymentResult(eventId, PaymentResult.Type.PAYMENT_APPROVED, r.id()))).isFalse();
        // A late rejection of a confirmed reservation: ignored.
        assertThat(service.applyPaymentResult(new PaymentResult(UUID.randomUUID(), PaymentResult.Type.PAYMENT_REJECTED, r.id()))).isFalse();
        assertThat(service.lookup(r.code()).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void aRejectedPaymentFreesTheSlot() {
        Reservation r = service.create(command(UUID.randomUUID(), TOMORROW, 18, 20));

        service.applyPaymentResult(new PaymentResult(UUID.randomUUID(), PaymentResult.Type.PAYMENT_REJECTED, r.id()));

        assertThat(service.lookup(r.code()).status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(slot(service.availability(COURT, TOMORROW), 18).available()).isTrue();
    }

    private static Availability.Slot slot(Availability availability, int hour) {
        return availability.slots().stream()
                .filter(s -> s.range().start().equals(LocalTime.of(hour, 0)))
                .findFirst().orElseThrow();
    }
}
