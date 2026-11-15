package com.futbolix.reservations.application.port.out;

import com.futbolix.reservations.domain.BookableDay;
import java.time.LocalDate;
import java.util.UUID;

/** The schedules domain (ftx-schedules-api), which also brings the court status and price. */
public interface BookableSlotsCatalog {

    /**
     * @throws com.futbolix.reservations.application.CourtNotFoundException       unknown court
     * @throws com.futbolix.reservations.application.DependencyUnavailableException no answer in time
     */
    BookableDay bookableDay(UUID courtId, LocalDate date);
}
