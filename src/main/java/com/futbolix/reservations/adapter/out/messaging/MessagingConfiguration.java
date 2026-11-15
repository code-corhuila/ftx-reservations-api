package com.futbolix.reservations.adapter.out.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology on RabbitMQ (02-domain/domain-events.md): the topic exchange futbolix.events, and the
 * queue where this service receives the payment results. A message that keeps failing goes to a
 * dead-letter queue instead of being requeued forever (default-requeue-rejected: false).
 * Turned off with RESERVATIONS_MESSAGING_ENABLED=false: then events wait in the outbox.
 */
@Configuration
@ConditionalOnProperty(name = "reservations.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfiguration {

    public static final String EXCHANGE = "futbolix.events";
    public static final String DEAD_LETTER_EXCHANGE = "futbolix.events.dlx";
    public static final String PAYMENT_RESULTS_QUEUE = "reservation.payment-results";
    public static final String PAYMENT_RESULTS_DLQ = "reservation.payment-results.dlq";

    @Bean
    Declarables reservationTopology() {
        TopicExchange events = new TopicExchange(EXCHANGE, true, false);
        FanoutExchange deadLetters = new FanoutExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue paymentResults = QueueBuilder.durable(PAYMENT_RESULTS_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(PAYMENT_RESULTS_DLQ).build();
        Binding approved = BindingBuilder.bind(paymentResults).to(events).with("payment.approved");
        Binding rejected = BindingBuilder.bind(paymentResults).to(events).with("payment.rejected");
        Binding dead = BindingBuilder.bind(deadLetterQueue).to(deadLetters);
        return new Declarables(events, deadLetters, paymentResults, deadLetterQueue, approved, rejected, dead);
    }
}
