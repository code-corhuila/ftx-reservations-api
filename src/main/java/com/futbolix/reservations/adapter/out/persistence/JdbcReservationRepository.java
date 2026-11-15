package com.futbolix.reservations.adapter.out.persistence;

import com.futbolix.reservations.application.SlotUnavailableException;
import com.futbolix.reservations.application.port.in.Page;
import com.futbolix.reservations.application.port.in.ReservationQuery;
import com.futbolix.reservations.application.port.out.ReservationRepository;
import com.futbolix.reservations.domain.CancelledBy;
import com.futbolix.reservations.domain.Reservation;
import com.futbolix.reservations.domain.ReservationStatus;
import com.futbolix.reservations.domain.TimeRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * reservations.reservation through Spring JDBC, connected as reservations_app (receives
 * reservations_writer from ftx-reservations-db: no DELETE, no DDL).
 * Double booking is decided by the exclusion constraint reservations_no_double_booking: its
 * SQLSTATE 23P01 becomes SlotUnavailableException (06-data/models.md).
 */
public class JdbcReservationRepository implements ReservationRepository {

    private static final String COLUMNS = """
            id, reservation_code, customer_name, customer_email, customer_phone, court_id, reservation_date,
            start_time, end_time, status, amount, currency, cancelled_at, cancelled_by, cancellation_reason,
            created_at, updated_at""";
    private static final String SELECT = "SELECT " + COLUMNS + " FROM reservations.reservation ";

    private final JdbcClient jdbc;

    public JdbcReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Reservation r) {
        try {
            jdbc.sql("""
                    INSERT INTO reservations.reservation
                        (id, reservation_code, customer_name, customer_email, customer_phone, court_id,
                         reservation_date, start_time, end_time, status, amount, currency, created_at, updated_at)
                    VALUES (:id, :code, :name, :email, :phone, :courtId, :date, :start, :end, :status, :amount,
                            :currency, :createdAt, :updatedAt)""")
                    .param("id", r.id())
                    .param("code", r.code())
                    .param("name", r.customer().name())
                    .param("email", r.customer().email())
                    .param("phone", r.customer().phone())
                    .param("courtId", r.courtId())
                    .param("date", r.date())
                    .param("start", r.range().start())
                    .param("end", r.range().end())
                    .param("status", r.status().name())
                    .param("amount", r.amount())
                    .param("currency", r.currency())
                    .param("createdAt", timestamp(r.createdAt()), Types.TIMESTAMP)
                    .param("updatedAt", timestamp(r.updatedAt()), Types.TIMESTAMP)
                    .update();
        } catch (DataAccessException e) {
            String state = SqlStates.of(e);
            if (SqlStates.EXCLUSION_VIOLATION.equals(state)) {
                throw new SlotUnavailableException();
            }
            if (SqlStates.UNIQUE_VIOLATION.equals(state) && SqlStates.mentions(e, "idx_reservation_reservation_code")) {
                throw new CodeAlreadyUsedException();
            }
            throw e;
        }
    }

    @Override
    public void updateStatus(Reservation r) {
        jdbc.sql("""
                UPDATE reservations.reservation
                   SET status = :status, cancelled_at = :cancelledAt, cancelled_by = :cancelledBy,
                       cancellation_reason = :reason
                 WHERE id = :id""")
                .param("status", r.status().name())
                .param("cancelledAt", timestamp(r.cancelledAt()), Types.TIMESTAMP)
                .param("cancelledBy", r.cancelledBy() == null ? null : r.cancelledBy().name(), Types.VARCHAR)
                .param("reason", r.cancellationReason(), Types.VARCHAR)
                .param("id", r.id())
                .update();
    }

    @Override
    public Optional<Reservation> findByCode(String code) {
        return jdbc.sql(SELECT + "WHERE reservation_code = :code").param("code", code).query(this::map).optional();
    }

    @Override
    public Optional<Reservation> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Reservation> lockByCode(String code) {
        return jdbc.sql(SELECT + "WHERE reservation_code = :code FOR UPDATE").param("code", code).query(this::map).optional();
    }

    @Override
    public Optional<Reservation> lockById(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id FOR UPDATE").param("id", id).query(this::map).optional();
    }

    @Override
    public List<Reservation> activeOn(UUID courtId, LocalDate date) {
        return jdbc.sql(SELECT + """
                        WHERE court_id = :courtId AND reservation_date = :date
                          AND status IN ('PENDING', 'CONFIRMED')
                        ORDER BY start_time""")
                .param("courtId", courtId)
                .param("date", date)
                .query(this::map)
                .list();
    }

    @Override
    public Page<Reservation> list(ReservationQuery q) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q.status() != null) {
            where.append(" AND status = :status");
            params.put("status", q.status().name());
        }
        if (q.courtId() != null) {
            where.append(" AND court_id = :courtId");
            params.put("courtId", q.courtId());
        }
        if (q.dateFrom() != null) {
            where.append(" AND reservation_date >= :dateFrom");
            params.put("dateFrom", q.dateFrom());
        }
        if (q.dateTo() != null) {
            where.append(" AND reservation_date <= :dateTo");
            params.put("dateTo", q.dateTo());
        }
        long total = jdbc.sql("SELECT count(*) FROM reservations.reservation " + where)
                .params(params).query(Long.class).single();
        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("limit", q.limit());
        pageParams.put("offset", q.offset());
        List<Reservation> items = jdbc.sql(SELECT + where + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .params(pageParams).query(this::map).list();
        return new Page<>(new ArrayList<>(items), q.page(), q.limit(), total);
    }

    @Override
    public Optional<IdempotentRequest> findIdempotentRequest(UUID key) {
        return jdbc.sql("""
                        SELECT request_hash, reservation_id FROM reservations.idempotency_key
                         WHERE idempotency_key = :key""")
                .param("key", key)
                .query((rs, n) -> new IdempotentRequest(rs.getString("request_hash"),
                        rs.getObject("reservation_id", UUID.class)))
                .optional();
    }

    @Override
    public void saveIdempotentRequest(UUID key, String requestHash, UUID reservationId) {
        try {
            jdbc.sql("""
                    INSERT INTO reservations.idempotency_key (idempotency_key, request_hash, reservation_id)
                    VALUES (:key, :hash, :reservationId)""")
                    .param("key", key)
                    .param("hash", requestHash)
                    .param("reservationId", reservationId)
                    .update();
        } catch (DataAccessException e) {
            if (SqlStates.UNIQUE_VIOLATION.equals(SqlStates.of(e))) {
                throw new IdempotencyKeyTakenException();
            }
            throw e;
        }
    }

    @Override
    public boolean markProcessed(UUID eventId, String eventType) {
        return jdbc.sql("""
                        INSERT INTO reservations.processed_event (event_id, event_type)
                        VALUES (:eventId, :eventType)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("eventId", eventId)
                .param("eventType", eventType)
                .update() == 1;
    }

    private Reservation map(ResultSet rs, int row) throws SQLException {
        String cancelledBy = rs.getString("cancelled_by");
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getString("reservation_code"),
                rs.getObject("court_id", UUID.class),
                rs.getObject("reservation_date", LocalDate.class),
                new TimeRange(rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class)),
                rs.getLong("amount"),
                rs.getString("currency"),
                new Reservation.Customer(rs.getString("customer_name"), rs.getString("customer_email"),
                        rs.getString("customer_phone")),
                ReservationStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("cancelled_at")),
                cancelledBy == null ? null : CancelledBy.valueOf(cancelledBy),
                rs.getString("cancellation_reason"));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
