package com.futbolix.reservations.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.futbolix.reservations.adapter.out.messaging.MessagingConfiguration;
import com.futbolix.reservations.application.port.in.PaymentResult;
import com.futbolix.reservations.application.port.in.ReservationUseCases;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes PaymentApproved and PaymentRejected (HU-RES-005). The reservation becomes CONFIRMED or
 * REJECTED only here, never through REST (BR-008, M8). A malformed message is sent to the
 * dead-letter queue at once: retrying it can never succeed.
 */
@Component
@ConditionalOnProperty(name = "reservations.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final ReservationUseCases useCases;
    private final ObjectMapper mapper;

    public PaymentEventsListener(ReservationUseCases useCases, ObjectMapper mapper) {
        this.useCases = useCases;
        this.mapper = mapper;
    }

    @RabbitListener(queues = MessagingConfiguration.PAYMENT_RESULTS_QUEUE)
    public void onPaymentResult(Message message) {
        PaymentResult result;
        try {
            result = parse(message.getBody());
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            log.warn("Malformed payment event sent to the dead-letter queue: {}", e.getClass().getSimpleName());
            throw new AmqpRejectAndDontRequeueException("Malformed payment event", e);
        }
        useCases.applyPaymentResult(result);
    }

    PaymentResult parse(byte[] body) throws IOException {
        JsonNode event = mapper.readTree(body);
        PaymentResult.Type type = switch (event.path("eventType").asText()) {
            case "PaymentApproved" -> PaymentResult.Type.PAYMENT_APPROVED;
            case "PaymentRejected" -> PaymentResult.Type.PAYMENT_REJECTED;
            default -> throw new IllegalArgumentException("Unexpected eventType");
        };
        return new PaymentResult(UUID.fromString(event.get("eventId").asText()), type,
                UUID.fromString(event.get("payload").get("reservationId").asText()));
    }
}
