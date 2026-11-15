package com.futbolix.reservations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TimeRangeTest {

    @Test
    void aRangeOfWholeHoursIsSplitIntoOneHourSlots() {
        TimeRange range = new TimeRange(LocalTime.of(18, 0), LocalTime.of(20, 0));

        assertThat(range.hours()).isEqualTo(2);
        assertThat(range.hourlySlots()).containsExactly(
                new TimeRange(LocalTime.of(18, 0), LocalTime.of(19, 0)),
                new TimeRange(LocalTime.of(19, 0), LocalTime.of(20, 0)));
    }

    @Test
    void halfHoursAreRejected() {
        assertThatThrownBy(() -> new TimeRange(LocalTime.of(18, 30), LocalTime.of(19, 30)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void theEndMustBeAfterTheStart() {
        assertThatThrownBy(() -> new TimeRange(LocalTime.of(20, 0), LocalTime.of(18, 0)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("field").isEqualTo("endTime");
    }

    @Test
    void adjacentRangesDoNotOverlap() {
        TimeRange first = new TimeRange(LocalTime.of(18, 0), LocalTime.of(20, 0));

        assertThat(first.overlaps(new TimeRange(LocalTime.of(20, 0), LocalTime.of(21, 0)))).isFalse();
        assertThat(first.overlaps(new TimeRange(LocalTime.of(19, 0), LocalTime.of(21, 0)))).isTrue();
    }
}
