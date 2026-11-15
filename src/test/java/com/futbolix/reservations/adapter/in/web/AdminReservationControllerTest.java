package com.futbolix.reservations.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.futbolix.reservations.application.port.in.Page;
import com.futbolix.reservations.application.port.in.ReservationUseCases;
import com.futbolix.reservations.domain.CancelledBy;
import com.futbolix.reservations.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Negative authorization tests: only a real administrator token reaches /api/v1/admin/**. */
@WebMvcTest(controllers = {ReservationController.class, AdminReservationController.class})
@Import({ApiExceptionHandler.class, CorrelationIdFilter.class, AdministratorInterceptor.class, WebConfiguration.class,
        TestVerifierConfiguration.class})
class AdminReservationControllerTest {

    static final String ADMIN_ID = "6f1c2b7e-0000-4000-8000-000000000007";

    @Autowired MockMvc mvc;
    @MockitoBean ReservationUseCases useCases;

    @Test
    void anAdministratorListsReservations() throws Exception {
        when(useCases.list(any())).thenReturn(new Page<>(List.of(ReservationControllerTest.reservation()), 1, 20, 1));

        mvc.perform(get("/api/v1/admin/reservations").param("status", "PENDING")
                        .header("Authorization", "Bearer " + TestTokens.administrator(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
    }

    @Test
    void withoutATokenIs401() throws Exception {
        mvc.perform(get("/api/v1/admin/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        verify(useCases, never()).list(any());
    }

    @Test
    void spoofedIdentityHeadersAreIgnored() throws Exception {
        mvc.perform(get("/api/v1/admin/reservations")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMINISTRATOR"))
                .andExpect(status().isUnauthorized());
        verify(useCases, never()).list(any());
    }

    @Test
    void anotherRoleIs403() throws Exception {
        mvc.perform(post("/api/v1/admin/reservations/" + UUID.randomUUID() + "/cancellation")
                        .header("Authorization", "Bearer " + TestTokens.withRole(ADMIN_ID, "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        verify(useCases, never()).cancelAsAdministrator(any(), any(), any());
    }

    @Test
    void expiredForgedOrForeignTokensAre401() throws Exception {
        for (String token : List.of(TestTokens.expired(ADMIN_ID), TestTokens.signedByAnotherKey(ADMIN_ID),
                TestTokens.fromIssuer(ADMIN_ID, "someone-else"))) {
            mvc.perform(get("/api/v1/admin/reservations").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verify(useCases, never()).list(any());
    }

    @Test
    void anAdministratorCancelsAndTheAuditKeepsWho() throws Exception {
        Reservation cancelled = ReservationControllerTest.reservation();
        cancelled.cancel(CancelledBy.ADMINISTRATOR, "Maintenance", Instant.parse("2030-01-02T00:00:00Z"));
        when(useCases.cancelAsAdministrator(eq(cancelled.id()), eq("Maintenance"), eq(ADMIN_ID))).thenReturn(cancelled);

        mvc.perform(post("/api/v1/admin/reservations/" + cancelled.id() + "/cancellation")
                        .contentType("application/json").content("{\"reason\": \"Maintenance\"}")
                        .header("Authorization", "Bearer " + TestTokens.administrator(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("ADMINISTRATOR"));
    }

    @Test
    void aLimitAboveOneHundredIs400() throws Exception {
        mvc.perform(get("/api/v1/admin/reservations").param("limit", "500")
                        .header("Authorization", "Bearer " + TestTokens.administrator(ADMIN_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("limit"));
    }
}
