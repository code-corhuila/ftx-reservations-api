package com.futbolix.reservations.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.futbolix.reservations.application.port.out.EventOutbox;
import com.futbolix.reservations.domain.Reservation;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes the event envelope of 02-domain/domain-events.md into reservations.outbox_event, inside
 * the caller's transaction (ADR-006). The relay publishes it to futbolix.events later.
 * The phone is never in an event (domain-events.md: Payment has no use for it).
 */
public class JdbcEventOutbox implements EventOutbox {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcEventOutbox(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void reservationCreated(Reservation r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", r.id().toString());
        payload.put("courtId", r.courtId().toString());
        payload.put("reservationDate", r.date().toString());
        payload.put("startTime", HOUR.format(r.range().start()));
        payload.put("endTime", HOUR.format(r.range().end()));
        payload.put("amount", r.amount());
        payload.put("currency", r.currency());
        payload.put("customerName", r.customer().name());
        payload.put("customerEmail", r.customer().email());
        append(r, "ReservationCreated", "reservation.created", payload);
    }

    @Override
    public void reservationCancelled(Reservation r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", r.id().toString());
        payload.put("cancelledBy", r.cancelledBy().name());
        append(r, "ReservationCancelled", "reservation.cancelled", payload);
    }

    private void append(Reservation r, String eventType, String routingKey, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = r.updatedAt() != null ? r.updatedAt() : Instant.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateId", r.id().toString());
        envelope.put("aggregateType", "Reservation");
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("version", 1);
        envelope.put("payload", payload);
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + eventType, e);
        }
        jdbc.sql("""
                INSERT INTO reservations.outbox_event (id, aggregate_id, event_type, routing_key, payload, occurred_at)
                VALUES (:id, :aggregateId, :eventType, :routingKey, CAST(:payload AS jsonb), :occurredAt)""")
                .param("id", eventId)
                .param("aggregateId", r.id())
                .param("eventType", eventType)
                .param("routingKey", routingKey)
                .param("payload", json)
                .param("occurredAt", Timestamp.from(occurredAt), Types.TIMESTAMP)
                .update();
    }
}
