package com.futbolix.reservations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReservationCodeTest {

    @Test
    void codesMatchTheContractAndDoNotRepeat() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = ReservationCode.generate();
            assertThat(ReservationCode.isWellFormed(code)).isTrue();
            assertThat(code).hasSize(ReservationCode.LENGTH).doesNotContain("0", "O", "1", "I");
            codes.add(code);
        }
        assertThat(codes).hasSize(10_000);
    }

    @Test
    void anythingOutsideTheFormatIsNotACode() {
        assertThat(ReservationCode.isWellFormed("ftx123")).isFalse();
        assertThat(ReservationCode.isWellFormed("FTX-123")).isFalse();
        assertThat(ReservationCode.isWellFormed("A".repeat(21))).isFalse();
        assertThat(ReservationCode.isWellFormed(null)).isFalse();
    }
}
