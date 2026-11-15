package com.futbolix.reservations.domain;

/** A well-formed request that breaks a business rule: answered 422 BUSINESS_RULE_VIOLATION. */
public class BusinessRuleViolationException extends RuntimeException {

    private final String field;

    public BusinessRuleViolationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
