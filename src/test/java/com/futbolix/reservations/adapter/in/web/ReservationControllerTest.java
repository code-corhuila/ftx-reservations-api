package com.futbolix.reservations.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.futbolix.reservations.application.CourtNotFoundException;
import com.futbolix.reservations.application.DependencyUnavailableException;
import com.futbolix.reservations.application.ReservationNotFoundException;
import com.futbolix.reservations.application.SlotUnavailableException;
import com.futbolix.reservations.application.port.in.Availability;
import com.futbolix.reservations.application.port.in.ReservationUseCases;
import com.futbolix.reservations.domain.BookableDay;
import com.futbolix.reservations.domain.BusinessRuleViolationException;
import com.futbolix.reservations.domain.Reservation;
import com.futbolix.reservations.domain.ReservationNotCancellableException;
import com.futbolix.reservations.domain.TimeRange;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ReservationController.class, AdminReservationController.class})
@Import({ApiExceptionHandler.class, CorrelationIdFilter.class, AdministratorInterceptor.class, WebConfiguration.class,
        TestVerifierConfiguration.class})
class ReservationControllerTest {

    static final UUID COURT = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final LocalDate DATE = LocalDate.of(2030, 1, 10);
    static final String BODY = """
            {"courtId": "c0000000-0000-4000-8000-000000000001", "reservationDate": "2030-01-10",
             "startTime": "18:00:00", "endTime": "20:00:00", "customerName": "Test Customer",
             "customerEmail": "customer@example.test", "customerPhone": "+573000000000"}""";

    @Autowired MockMvc mvc;
    @MockitoBean ReservationUseCases useCases;

    static Reservation reservation() {
        TimeRange range = new TimeRange(LocalTime.of(18, 0), LocalTime.of(20, 0));
        return Reservation.pending(COURT, DATE, range,
                new BookableDay(COURT, DATE, "ACTIVE", 60_000, "COP", range.hourlySlots()),
                new Reservation.Customer("Test Customer", "customer@example.test", "+573000000000"),
                Instant.parse("2030-01-01T12:00:00Z"));
    }

    @Test
    void createsAReservationAndNeverCachesTheCode() throws Exception {
        when(useCases.create(any())).thenReturn(reservation());

        mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.startTime").value("18:00:00"))
                .andExpect(jsonPath("$.amount.amount").value(120000))
                .andExpect(jsonPath("$.amount.currency").value("COP"));
    }

    @Test
    void theIdempotencyKeyIsRequired() throws Exception {
        mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
        verify(useCases, never()).create(any());
    }

    @Test
    void theClientCannotSendTheAmount() throws Exception {
        String withAmount = BODY.replace("}", ", \"amount\": {\"amount\": 1, \"currency\": \"COP\"}}");

        mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(withAmount))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("amount"));
        verify(useCases, never()).create(any());
    }

    @Test
    void anInvalidEmailOrTimeIsA400() throws Exception {
        mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("customer@example.test", "not-an-email").replace("18:00:00", "6pm")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void anOverlapIs409RESERVATION_SLOT_UNAVAILABLE() throws Exception {
        when(useCases.create(any())).thenThrow(new SlotUnavailableException());

        mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESERVATION_SLOT_UNAVAILABLE"));
    }

    @Test
    void aBrokenBusinessRuleIs422() throws Exception {
        when(useCases.create(any())).thenThrow(new BusinessRuleViolationException("startTime", "The start time is in the past"));

        mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.details[0].field").value("startTime"));
    }

    @Test
    void anUnknownCourtIs404() throws Exception {
        when(useCases.create(any())).thenThrow(new CourtNotFoundException());

        mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("COURT_NOT_FOUND"));
    }

    @Test
    void aSilentDependencyIs503() throws Exception {
        when(useCases.availability(eq(COURT), eq(DATE)))
                .thenThrow(new DependencyUnavailableException("ftx-schedules-api", null));

        mvc.perform(get("/api/v1/availability").param("courtId", COURT.toString()).param("date", "2030-01-10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void availabilityListsEverySlot() throws Exception {
        when(useCases.availability(eq(COURT), eq(DATE))).thenReturn(new Availability(COURT, DATE, 60_000, "COP",
                List.of(new Availability.Slot(new TimeRange(LocalTime.of(18, 0), LocalTime.of(19, 0)), false),
                        new Availability.Slot(new TimeRange(LocalTime.of(19, 0), LocalTime.of(20, 0)), true))));

        mvc.perform(get("/api/v1/availability").param("courtId", COURT.toString()).param("date", "2030-01-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvailability").value(true))
                .andExpect(jsonPath("$.hourlyPrice.amount").value(60000))
                .andExpect(jsonPath("$.slots[0].available").value(false))
                .andExpect(jsonPath("$.slots[1].startTime").value("19:00:00"));
    }

    @Test
    void anUnknownCodeGetsTheGenericNotFound() throws Exception {
        when(useCases.lookup("ZZZZZZZZZZ")).thenThrow(new ReservationNotFoundException());

        mvc.perform(get("/api/v1/reservations/ZZZZZZZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aFinalReservationCannotBeCancelled() throws Exception {
        when(useCases.cancelAsCustomer(eq("ABCDEFGHJK"), any())).thenThrow(new ReservationNotCancellableException());

        mvc.perform(post("/api/v1/reservations/ABCDEFGHJK/cancellation"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESERVATION_NOT_CANCELLABLE"));
    }
}
