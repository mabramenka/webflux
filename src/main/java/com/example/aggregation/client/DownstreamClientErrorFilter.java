package com.example.aggregation.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

public final class DownstreamClientErrorFilter {

    private DownstreamClientErrorFilter() {
    }

    public static ExchangeFilterFunction forClient(String clientName) {
        return forClient(clientName, null);
    }

    public static ExchangeFilterFunction forClient(String clientName, MeterRegistry meterRegistry) {
        return (request, next) -> next.exchange(request)
            .doOnError(ex -> record(meterRegistry, clientName, "IO_ERROR", "ERROR"))
            .flatMap(response -> {
                record(meterRegistry, clientName, status(response.statusCode()), outcome(response.statusCode()));
                if (!response.statusCode().isError()) {
                    return Mono.just(response);
                }
                var statusCode = response.statusCode();
                return response.bodyToMono(String.class)
                    .filter(message -> !message.isBlank())
                    .defaultIfEmpty(defaultErrorMessage(clientName))
                    .flatMap(message -> Mono.error(new DownstreamClientException(clientName, statusCode, message)));
            });
    }

    private static void record(MeterRegistry meterRegistry, String clientName, String status, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("aggregation.downstream.requests")
            .tag("client", clientName)
            .tag("status", status)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    private static String status(HttpStatusCode statusCode) {
        return Integer.toString(statusCode.value());
    }

    private static String outcome(HttpStatusCode statusCode) {
        return statusCode.isError() ? "ERROR" : "SUCCESS";
    }

    private static String defaultErrorMessage(String clientName) {
        return clientName.substring(0, 1).toLowerCase() + clientName.substring(1) + " client request failed";
    }
}
