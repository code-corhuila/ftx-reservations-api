package com.futbolix.reservations.domain;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The reservation code is the customer's only key, so it works as a secret (ADR-007,
 * security-rules.md A01/A02): SecureRandom, never sequential or time-based. 10 characters from an
 * alphabet without look-alikes (no 0/O, 1/I) give 32^10 ≈ 1.1e15 codes, so guessing one is useless.
 */
public final class ReservationCode {

    public static final int LENGTH = 10;
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{1,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ReservationCode() {
    }

    public static String generate() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    /** Shape of reservation-service.yaml (ReservationCode). Anything else cannot exist. */
    public static boolean isWellFormed(String code) {
        return code != null && FORMAT.matcher(code).matches();
    }
}
