package com.futbolix.reservations.application.port.in;

import java.util.UUID;

/** PaymentApproved or PaymentRejected, as consumed from futbolix.events. */
public record PaymentResult(UUID eventId, Type type, UUID reservationId) {

    public enum Type {
        PAYMENT_APPROVED("PaymentApproved"), PAYMENT_REJECTED("PaymentRejected");

        private final String eventType;

        Type(String eventType) {
            this.eventType = eventType;
        }

        public String eventType() {
            return eventType;
        }
    }
}
