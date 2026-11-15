package com.futbolix.reservations.adapter.in.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.futbolix.reservations.application.CourtNotFoundException;
import com.futbolix.reservations.application.DependencyUnavailableException;
import com.futbolix.reservations.application.IdempotencyKeyReusedException;
import com.futbolix.reservations.application.ReservationNotFoundException;
import com.futbolix.reservations.application.SlotUnavailableException;
import com.futbolix.reservations.domain.BusinessRuleViolationException;
import com.futbolix.reservations.domain.ReservationNotCancellableException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to the ErrorResponse of _shared.yaml with the error codes of
 * reservation-service.yaml. Never leaks internals, stack traces or customer data (A05/A09).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // ── 400 ──────────────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        return validation(e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.Detail(f.getField(), f.getDefaultMessage()))
                .toList());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return validation(List.of(new ErrorResponse.Detail(path(unknown), "Unknown field")));
        }
        if (cause instanceof InvalidFormatException invalid) {
            return validation(List.of(new ErrorResponse.Detail(path(invalid), "Invalid value")));
        }
        return validation(List.of(new ErrorResponse.Detail(null, "The body must be a JSON object")));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        return validation(List.of(new ErrorResponse.Detail(e.getHeaderName(), "Required header")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException e) {
        return validation(List.of(new ErrorResponse.Detail(e.getParameterName(), "Required parameter")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> badValue(MethodArgumentTypeMismatchException e) {
        return validation(List.of(new ErrorResponse.Detail(e.getName(), "Invalid value")));
    }

    @ExceptionHandler(AdminReservationController.InvalidParameterException.class)
    ResponseEntity<ErrorResponse> invalidParameter(AdminReservationController.InvalidParameterException e) {
        return validation(List.of(new ErrorResponse.Detail(e.field(), e.getMessage())));
    }

    // ── 401 / 403 / 503 of the administrator guard ───────────────────────────────────────────

    @ExceptionHandler(AdministratorInterceptor.AuthenticationRequiredException.class)
    ResponseEntity<ErrorResponse> unauthenticated() {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required", List.of());
    }

    @ExceptionHandler(AdministratorInterceptor.AdministratorRequiredException.class)
    ResponseEntity<ErrorResponse> forbidden() {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Administrator authorization is required", List.of());
    }

    @ExceptionHandler(AdministratorTokenVerifier.KeysUnavailableException.class)
    ResponseEntity<ErrorResponse> keysUnavailable() {
        log.warn("Administrator token keys unavailable");
        return unavailable();
    }

    // ── Domain ───────────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(CourtNotFoundException.class)
    ResponseEntity<ErrorResponse> courtNotFound() {
        return error(HttpStatus.NOT_FOUND, "COURT_NOT_FOUND", "The requested court was not found", List.of());
    }

    /** Unknown code, malformed code or unknown id: the same generic answer (ADR-007). */
    @ExceptionHandler({ReservationNotFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorResponse> notFound() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found", List.of());
    }

    @ExceptionHandler(SlotUnavailableException.class)
    ResponseEntity<ErrorResponse> slotUnavailable() {
        return error(HttpStatus.CONFLICT, "RESERVATION_SLOT_UNAVAILABLE",
                "The requested time slot is no longer available", List.of());
    }

    @ExceptionHandler(ReservationNotCancellableException.class)
    ResponseEntity<ErrorResponse> notCancellable() {
        return error(HttpStatus.CONFLICT, "RESERVATION_NOT_CANCELLABLE",
                "The reservation can no longer be cancelled", List.of());
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ResponseEntity<ErrorResponse> businessRule(BusinessRuleViolationException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "The reservation does not satisfy the required business rules",
                List.of(new ErrorResponse.Detail(e.field(), e.getMessage())));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ErrorResponse> idempotencyKeyReused() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "The Idempotency-Key was already used with a different request",
                List.of(new ErrorResponse.Detail("Idempotency-Key", "Use a new key for a new reservation")));
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ErrorResponse> dependencyUnavailable(DependencyUnavailableException e) {
        log.warn("Dependency unavailable: {}", e.dependency());
        return unavailable();
    }

    // ── HTTP and fallback ────────────────────────────────────────────────────────────────────

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed() {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "The method is not allowed on this resource", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedMediaType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send the body as application/json", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected internal server error occurred", List.of());
    }

    private static ResponseEntity<ErrorResponse> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "The service is temporarily unavailable", List.of());
    }

    private static ResponseEntity<ErrorResponse> validation(List<ErrorResponse.Detail> details) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request data", details);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
                                                       List<ErrorResponse.Detail> details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, details, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private static String path(JsonMappingException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
