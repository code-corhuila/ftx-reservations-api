package com.futbolix.reservations.adapter.in.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/health with the body of reservation-service.yaml.
 * The database is required: without it the answer is 503. The broker is not: if RabbitMQ is down
 * the service keeps creating reservations and the events wait in the outbox, so it is reported as
 * "degraded" and the status stays "ok". The container probes use /health/liveness and /readiness.
 */
@RestController
public class HealthController {

    private final JdbcClient jdbc;
    private final ObjectProvider<ConnectionFactory> rabbit;
    private final boolean messagingEnabled;

    public HealthController(JdbcClient jdbc, ObjectProvider<ConnectionFactory> rabbit,
                            @Value("${reservations.messaging.enabled:true}") boolean messagingEnabled) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.messagingEnabled = messagingEnabled;
    }

    @GetMapping("/api/v1/health")
    ResponseEntity<?> health() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SERVICE_UNAVAILABLE", "The service is temporarily unavailable", null, null));
        }
        Map<String, String> dependencies = new LinkedHashMap<>();
        dependencies.put("database", "ok");
        if (messagingEnabled) {
            dependencies.put("broker", brokerStatus());
        }
        return ResponseEntity.ok(new Health("ok", Instant.now(), dependencies));
    }

    private String brokerStatus() {
        ConnectionFactory factory = rabbit.getIfAvailable();
        if (factory == null) {
            return "down";
        }
        try (Connection connection = factory.createConnection()) {
            return connection.isOpen() ? "ok" : "degraded";
        } catch (RuntimeException e) {
            return "degraded";
        }
    }

    record Health(String status, Instant timestamp, Map<String, String> dependencies) {
    }
}
