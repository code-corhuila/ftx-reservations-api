package com.futbolix.reservations.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relay of the transactional outbox (ADR-006, HU-RES-006). Every second it takes the oldest
 * pending events (FOR UPDATE SKIP LOCKED, so two replicas never publish the same row), publishes
 * each one to futbolix.events waiting for the broker's confirm, and marks it published.
 *
 * If RabbitMQ is down the row stays pending with attempts and last_error, and it is published when
 * the broker comes back: no event is lost and creating reservations never depends on the broker.
 * Delivery is at-least-once; consumers deduplicate by eventId (the AMQP messageId).
 */
@Component
@ConditionalOnProperty(name = "reservations.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final long confirmTimeoutMillis;

    public OutboxRelay(JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate transactions,
                       @Value("${reservations.outbox.batch-size:50}") int batchSize,
                       @Value("${reservations.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transactions = transactions;
        this.batchSize = batchSize;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    record PendingEvent(UUID id, String eventType, String routingKey, String payload) {
    }

    @Scheduled(fixedDelayString = "${reservations.outbox.relay-interval-ms:1000}")
    public void publishPending() {
        transactions.executeWithoutResult(status -> {
            List<PendingEvent> pending = jdbc.sql("""
                            SELECT id, event_type, routing_key, payload::text AS payload
                              FROM reservations.outbox_event
                             WHERE published_at IS NULL
                             ORDER BY occurred_at
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED""")
                    .param("limit", batchSize)
                    .query((rs, n) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                            rs.getString("routing_key"), rs.getString("payload")))
                    .list();
            for (PendingEvent event : pending) {
                try {
                    publish(event);
                    jdbc.sql("""
                            UPDATE reservations.outbox_event
                               SET published_at = now(), attempts = attempts + 1, last_error = NULL
                             WHERE id = :id""").param("id", event.id()).update();
                    log.info("Event published eventId={} type={}", event.id(), event.eventType());
                } catch (AmqpException e) {
                    jdbc.sql("""
                            UPDATE reservations.outbox_event
                               SET attempts = attempts + 1, last_error = :error
                             WHERE id = :id""")
                            .param("error", truncate(e.getClass().getSimpleName() + ": " + e.getMessage()))
                            .param("id", event.id())
                            .update();
                    log.warn("Broker unavailable, event kept in the outbox eventId={} type={}", event.id(),
                            event.eventType());
                    return;   // keep the order: try the rest on the next round
                }
            }
        });
    }

    private void publish(PendingEvent event) {
        Message message = MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.id().toString())
                .setType(event.eventType())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        rabbit.invoke(operations -> {
            operations.send(MessagingConfiguration.EXCHANGE, event.routingKey(), message);
            operations.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private static String truncate(String value) {
        return value == null ? null : value.length() <= 255 ? value : value.substring(0, 255);
    }
}
