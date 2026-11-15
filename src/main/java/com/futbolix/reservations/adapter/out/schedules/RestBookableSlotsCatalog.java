package com.futbolix.reservations.adapter.out.schedules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.futbolix.reservations.adapter.in.web.CorrelationIdFilter;
import com.futbolix.reservations.application.CourtNotFoundException;
import com.futbolix.reservations.application.DependencyUnavailableException;
import com.futbolix.reservations.application.port.out.BookableSlotsCatalog;
import com.futbolix.reservations.domain.BookableDay;
import com.futbolix.reservations.domain.TimeRange;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client of ftx-schedules-api: GET /api/v1/courts/{id}/bookable-slots?date=, which brings the
 * court status and current price from ftx-courts-api too. Short timeouts and no guessing: if the
 * schedules domain does not answer, the caller gets 503 (13-operations, fail fast).
 */
public class RestBookableSlotsCatalog implements BookableSlotsCatalog {

    static final String DEPENDENCY = "ftx-schedules-api";

    private final RestClient client;

    public RestBookableSlotsCatalog(String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public BookableDay bookableDay(UUID courtId, LocalDate date) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        SlotsView view;
        try {
            view = client.get()
                    .uri("/api/v1/courts/{id}/bookable-slots?date={date}", courtId, date)
                    .headers(h -> {
                        if (correlationId != null) {
                            h.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                        throw new CourtNotFoundException();
                    })
                    .body(SlotsView.class);
        } catch (RestClientException e) {
            throw new DependencyUnavailableException(DEPENDENCY, e);
        }
        if (view == null || view.hourlyPrice() == null || view.slots() == null) {
            throw new DependencyUnavailableException(DEPENDENCY, null);
        }
        return new BookableDay(courtId, date, view.courtStatus(), view.hourlyPrice().amount(),
                view.hourlyPrice().currency(),
                view.slots().stream()
                        .map(s -> new TimeRange(LocalTime.parse(s.startTime()), LocalTime.parse(s.endTime())))
                        .toList());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SlotsView(UUID courtId, LocalDate date, String courtStatus, MoneyView hourlyPrice, List<SlotView> slots) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MoneyView(long amount, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SlotView(String startTime, String endTime) {
    }
}
