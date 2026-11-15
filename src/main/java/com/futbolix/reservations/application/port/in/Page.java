package com.futbolix.reservations.application.port.in;

import java.util.List;

public record Page<T>(List<T> items, int page, int limit, long total) {

    public int totalPages() {
        return total == 0 ? 0 : (int) ((total + limit - 1) / limit);
    }
}
