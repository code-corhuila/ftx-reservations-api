package com.futbolix.reservations.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.futbolix.reservations.application.ReservationService;
import com.futbolix.reservations.application.SlotUnavailableException;
import com.futbolix.reservations.application.port.in.CreateReservationCommand;
import com.futbolix.reservations.application.port.out.Transactions;
import com.futbolix.reservations.domain.BookableDay;
import com.futbolix.reservations.domain.CancelledBy;
import com.futbolix.reservations.domain.Reservation;
import com.futbolix.reservations.domain.ReservationStatus;
import com.futbolix.reservations.domain.TimeRange;
import java.io.File;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Against a real PostgreSQL with the REAL migrations of the sibling ftx-reservations-db clone,
 * connected as reservations_app with only the rights ftx-reservations-db grants it.
 *
 * Double booking is a race, so it cannot be proven with a unit test (06-data/models.md): here
 * eight concurrent transactions try the same slot and exactly one must survive. Removing the
 * exclusion constraint makes this test fail.
 *
 * Database: FTX_IT_JDBC_URL / FTX_IT_ADMIN_USER / FTX_IT_ADMIN_PASSWORD when given (the reservations
 * schema there is dropped and rebuilt: never point it at a shared database), otherwise a
 * Testcontainers PostgreSQL 16 when Docker is available, otherwise the tests are skipped.
 */
class ReservationDatabaseIntegrationTest {

    static final UUID COURT = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final LocalDate DATE = LocalDate.of(2030, 1, 10);

    static PostgreSQLContainer<?> container;
    static DriverManagerDataSource appDataSource;
    static JdbcClient jdbc;
    static JdbcReservationRepository repository;
    static TransactionTemplate transactions;

    @BeforeAll
    static void startDatabase() {
        File dbDir = new File(System.getProperty("reservations.db.dir", "../ftx-reservations-db"));
        Assumptions.assumeTrue(new File(dbDir, "01_ddl").isDirectory(),
                "ftx-reservations-db clone not found at " + dbDir.getAbsolutePath());

        String url = System.getenv("FTX_IT_JDBC_URL");
        String adminUser = System.getenv("FTX_IT_ADMIN_USER");
        String adminPassword = System.getenv("FTX_IT_ADMIN_PASSWORD");
        if (url == null) {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "No FTX_IT_JDBC_URL and no Docker: integration tests skipped");
            container = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("ftx");
            container.start();
            url = container.getJdbcUrl();
            adminUser = container.getUsername();
            adminPassword = container.getPassword();
        }

        // What ftx-infra-postgres does: create the login user. Its password is made for this run.
        String appPassword = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
        JdbcClient admin = JdbcClient.create(new DriverManagerDataSource(url, adminUser, adminPassword));
        admin.sql("DROP SCHEMA IF EXISTS reservations CASCADE").update();
        admin.sql("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reservations_app') "
                + "THEN CREATE ROLE reservations_app LOGIN; END IF; END $$").update();
        admin.sql("ALTER ROLE reservations_app PASSWORD '" + appPassword + "'").update();

        Flyway.configure()
                .dataSource(url, adminUser, adminPassword)
                .locations("filesystem:" + dbDir + "/01_ddl", "filesystem:" + dbDir + "/02_dml",
                        "filesystem:" + dbDir + "/03_dcl", "filesystem:" + dbDir + "/04_tcl")
                .schemas("reservations")
                .defaultSchema("reservations")
                .load()
                .migrate();

        appDataSource = new DriverManagerDataSource(url, "reservations_app", appPassword);
        jdbc = JdbcClient.create(appDataSource);
        repository = new JdbcReservationRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    static Reservation pending(int day, int from, int to) {
        TimeRange range = new TimeRange(LocalTime.of(from, 0), LocalTime.of(to, 0));
        LocalDate date = DATE.plusDays(day);
        return Reservation.pending(COURT, date, range,
                new BookableDay(COURT, date, "ACTIVE", 60_000, "COP", range.hourlySlots()),
                new Reservation.Customer("Test Customer", "customer@example.test", "3000000000"), Instant.now());
    }

    @Test
    void eightConcurrentRequestsForTheSameSlotLeaveExactlyOneReservation() throws Exception {
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            Reservation candidate = pending(1, 18, 20);
            results.add(pool.submit(() -> {
                start.await();
                try {
                    transactions.executeWithoutResult(s -> repository.insert(candidate));
                    return true;
                } catch (SlotUnavailableException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Boolean> result : results) {
            created += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(repository.activeOn(COURT, DATE.plusDays(1))).hasSize(1);
    }

    @Test
    void aCancelledReservationFreesItsSlot() {
        Reservation first = pending(2, 18, 19);
        repository.insert(first);
        assertThatThrownBy(() -> repository.insert(pending(2, 18, 19))).isInstanceOf(SlotUnavailableException.class);

        first.cancel(CancelledBy.CUSTOMER, "Test", Instant.now());
        repository.updateStatus(first);
        repository.insert(pending(2, 18, 19));

        Reservation stored = repository.findByCode(first.code()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(stored.cancelledBy()).isEqualTo(CancelledBy.CUSTOMER);
        assertThat(repository.activeOn(COURT, DATE.plusDays(2))).hasSize(1);
    }

    @Test
    void creatingThroughTheUseCaseWritesTheReservationTheKeyAndTheEventTogether() throws Exception {
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return transactions.execute(s -> work.get());
            }
        };
        LocalDate date = DATE.plusDays(3);
        ReservationService service = new ReservationService(repository,
                (courtId, d) -> new BookableDay(courtId, d, "ACTIVE", 60_000, "COP",
                        new TimeRange(LocalTime.of(6, 0), LocalTime.of(22, 0)).hourlySlots()),
                new JdbcEventOutbox(jdbc, new ObjectMapper()), tx, Clock.system(ZoneId.of("America/Bogota")));
        UUID key = UUID.randomUUID();
        CreateReservationCommand command = new CreateReservationCommand(key, COURT, date, LocalTime.of(10, 0),
                LocalTime.of(12, 0), "Test Customer", "customer@example.test", "3000000000");

        Reservation created = service.create(command);
        Reservation replayed = service.create(command);

        assertThat(replayed.id()).isEqualTo(created.id());
        String payload = jdbc.sql("SELECT payload::text FROM reservations.outbox_event WHERE aggregate_id = :id")
                .param("id", created.id()).query(String.class).single();
        JsonNode event = new ObjectMapper().readTree(payload);
        assertThat(event.get("eventType").asText()).isEqualTo("ReservationCreated");
        assertThat(event.get("payload").get("amount").asLong()).isEqualTo(120_000L);
        assertThat(event.get("payload").has("customerPhone")).isFalse();   // the phone never travels in an event
        assertThat(payload).doesNotContain("3000000000");
    }

    @Test
    void aPaymentEventIsProcessedOnlyOnce() {
        UUID eventId = UUID.randomUUID();

        Boolean first = transactions.execute(s -> repository.markProcessed(eventId, "PaymentApproved"));
        Boolean again = transactions.execute(s -> repository.markProcessed(eventId, "PaymentApproved"));

        assertThat(first).isTrue();
        assertThat(again).isFalse();
    }

    @Test
    void theApplicationUserCannotDeleteHistory() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM reservations.reservation").update())
                .isInstanceOf(DataAccessException.class);
    }
}
