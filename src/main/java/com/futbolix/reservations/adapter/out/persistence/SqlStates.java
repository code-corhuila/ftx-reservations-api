package com.futbolix.reservations.adapter.out.persistence;

import java.sql.SQLException;

/** Reads the PostgreSQL SQLSTATE and message behind a Spring DataAccessException. */
final class SqlStates {

    static final String EXCLUSION_VIOLATION = "23P01";
    static final String UNIQUE_VIOLATION = "23505";

    private SqlStates() {
    }

    static String of(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    static boolean mentions(Throwable error, String constraint) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
