package com.futbolix.reservations.application.port.out;

import java.util.function.Supplier;

/** Runs a unit of work in one database transaction. */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
