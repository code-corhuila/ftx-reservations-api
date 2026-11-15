package com.futbolix.reservations.adapter.in.web;

import com.futbolix.reservations.application.port.in.CreateReservationCommand;
import com.futbolix.reservations.application.port.in.ReservationUseCases;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public operations of reservation-service.yaml. Customers are anonymous: the reservation code is
 * their only key, so responses carrying it are never cached (Cache-Control: no-store) and there is
 * no operation that lists or searches reservations publicly.
 */
@RestController
@RequestMapping("/api/v1")
public class ReservationController {

    private final ReservationUseCases useCases;

    public ReservationController(ReservationUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping("/availability")
    public AvailabilityResponse availability(@RequestParam UUID courtId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return AvailabilityResponse.from(useCases.availability(courtId, date));
    }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                      @Valid @RequestBody CreateReservationRequest body) {
        CreateReservationCommand command = new CreateReservationCommand(idempotencyKey, body.courtId(),
                body.reservationDate(), LocalTime.parse(body.startTime()), LocalTime.parse(body.endTime()),
                body.customerName(), body.customerEmail(), body.customerPhone());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ReservationResponse.from(useCases.create(command)));
    }

    @GetMapping("/reservations/{reservationCode}")
    public ResponseEntity<ReservationResponse> lookup(@PathVariable String reservationCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ReservationResponse.from(useCases.lookup(reservationCode)));
    }

    @PostMapping("/reservations/{reservationCode}/cancellation")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable String reservationCode,
                                                      @Valid @RequestBody(required = false) CancelReservationRequest body) {
        String reason = body == null ? null : body.reason();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ReservationResponse.from(useCases.cancelAsCustomer(reservationCode, reason)));
    }
}
