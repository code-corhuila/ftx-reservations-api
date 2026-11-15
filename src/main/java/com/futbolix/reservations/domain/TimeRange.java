package com.futbolix.reservations.domain;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** A range of whole hours inside one day, [start, end). BR-004. */
public record TimeRange(LocalTime start, LocalTime end) {

    public TimeRange {
        if (start == null || end == null) {
            throw new BusinessRuleViolationException("startTime", "Start and end times are required");
        }
        if (!end.isAfter(start)) {
            throw new BusinessRuleViolationException("endTime", "The end time must be after the start time");
        }
        if (!isWholeHour(start) || !isWholeHour(end)) {
            throw new BusinessRuleViolationException("startTime", "The range must cover a whole number of hours");
        }
    }

    public static boolean isWholeHour(LocalTime time) {
        return time.truncatedTo(ChronoUnit.HOURS).equals(time);
    }

    public int hours() {
        return (int) Duration.between(start, end).toHours();
    }

    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    /** The one-hour slots this range is made of: 18-20 is [18-19, 19-20]. */
    public List<TimeRange> hourlySlots() {
        List<TimeRange> slots = new ArrayList<>();
        for (LocalTime t = start; t.isBefore(end); t = t.plusHours(1)) {
            slots.add(new TimeRange(t, t.plusHours(1)));
        }
        return slots;
    }
}
