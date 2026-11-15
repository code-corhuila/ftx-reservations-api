package com.futbolix.reservations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // the outbox relay (adapter/out/messaging/OutboxRelay)
public class ReservationsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationsApiApplication.class, args);
    }
}
