package com.futbolix.reservations.adapter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.futbolix.reservations.adapter.out.persistence.JdbcEventOutbox;
import com.futbolix.reservations.adapter.out.persistence.JdbcReservationRepository;
import com.futbolix.reservations.adapter.out.schedules.RestBookableSlotsCatalog;
import com.futbolix.reservations.application.ReservationService;
import com.futbolix.reservations.application.port.out.BookableSlotsCatalog;
import com.futbolix.reservations.application.port.out.EventOutbox;
import com.futbolix.reservations.application.port.out.ReservationRepository;
import com.futbolix.reservations.application.port.out.Transactions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring of the hexagon: the use cases only see their ports. */
@Configuration
public class ReservationsConfiguration {

    /** Business time of the facility: "the past" is decided in America/Bogota (HU-RES-002). */
    @Bean
    Clock businessClock(@Value("${reservations.time-zone:America/Bogota}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    @Bean
    Transactions transactions(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        return new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    @Bean
    ReservationRepository reservationRepository(JdbcClient jdbc) {
        return new JdbcReservationRepository(jdbc);
    }

    @Bean
    EventOutbox eventOutbox(JdbcClient jdbc, ObjectMapper mapper) {
        return new JdbcEventOutbox(jdbc, mapper);
    }

    @Bean
    BookableSlotsCatalog bookableSlotsCatalog(@Value("${reservations.schedules-api.url}") String baseUrl,
                                              @Value("${reservations.schedules-api.timeout:2s}") Duration timeout) {
        return new RestBookableSlotsCatalog(baseUrl, timeout);
    }

    @Bean
    ReservationService reservationService(ReservationRepository repository, BookableSlotsCatalog catalog,
                                          EventOutbox outbox, Transactions transactions, Clock clock) {
        return new ReservationService(repository, catalog, outbox, transactions, clock);
    }
}
