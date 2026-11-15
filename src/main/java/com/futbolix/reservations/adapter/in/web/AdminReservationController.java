package com.futbolix.reservations.adapter.in.web;

import com.futbolix.reservations.application.port.in.Page;
import com.futbolix.reservations.application.port.in.ReservationQuery;
import com.futbolix.reservations.application.port.in.ReservationUseCases;
import com.futbolix.reservations.domain.Reservation;
import com.futbolix.reservations.domain.ReservationStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator operations. Every path is under /api/v1/admin/**, guarded by
 * {@link AdministratorInterceptor}: a valid RS256 token from ftx-customers-api with the
 * ADMINISTRATOR role, or 401/403. X-User-* headers are never trusted.
 */
@RestController
@RequestMapping("/api/v1/admin/reservations")
public class AdminReservationController {

    static final int MAX_LIMIT = 100;

    private final ReservationUseCases useCases;

    public AdminReservationController(ReservationUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping
    public ResponseEntity<PageResponse> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int limit,
                                             @RequestParam(required = false) ReservationStatus status,
                                             @RequestParam(required = false) UUID courtId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        if (page < 1) {
            throw new InvalidParameterException("page", "must be 1 or greater");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidParameterException("limit", "must be between 1 and " + MAX_LIMIT);
        }
        if (dateFrom != null && dateTo != null && dateTo.isBefore(dateFrom)) {
            throw new InvalidParameterException("dateTo", "must not be before dateFrom");
        }
        Page<Reservation> result = useCases.list(new ReservationQuery(page, limit, status, courtId, dateFrom, dateTo));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(
                result.items().stream().map(ReservationResponse::from).toList(),
                new PageResponse.Meta(result.page(), result.limit(), result.total(), result.totalPages())));
    }

    @PostMapping("/{id}/cancellation")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable UUID id,
                                                      @Valid @RequestBody(required = false) CancelReservationRequest body,
                                                      HttpServletRequest request) {
        String administratorId = (String) request.getAttribute(AdministratorInterceptor.ADMINISTRATOR_ID_ATTRIBUTE);
        String reason = body == null ? null : body.reason();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ReservationResponse.from(useCases.cancelAsAdministrator(id, reason, administratorId)));
    }

    public record PageResponse(List<ReservationResponse> data, Meta meta) {
        public record Meta(int page, int limit, long total, int totalPages) {
        }
    }

    /** A query parameter out of range: 400 VALIDATION_ERROR. */
    public static class InvalidParameterException extends RuntimeException {
        private final String field;

        InvalidParameterException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
