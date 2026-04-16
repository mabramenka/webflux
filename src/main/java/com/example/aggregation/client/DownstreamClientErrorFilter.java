package com.example.aggregation.client;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

public final class DownstreamClientErrorFilter {

    private DownstreamClientErrorFilter() {
    }

    public static ExchangeFilterFunction forClient(String clientName) {
        return forClient(clientName, (status, outcome) -> {
        });
    }

    public static ExchangeFilterFunction forClient(String clientName, MeterRegistry meterRegistry) {
        return forClient(clientName, (status, outcome) -> incrementCounter(meterRegistry, clientName, status, outcome));
    }

    private static ExchangeFilterFunction forClient(String clientName, MetricRecorder metricRecorder) {
        return (request, next) -> next.exchange(request)
            .onErrorMap(ex -> {
                metricRecorder.capture("IO_ERROR", "ERROR");
                return new DownstreamClientException(
                    clientName,
                    HttpStatus.BAD_GATEWAY,
                    defaultErrorMessage(clientName),
                    ex
                );
            })
            .flatMap(response -> {
                metricRecorder.capture(status(response.statusCode()), outcome(response.statusCode()));
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

    private static void incrementCounter(MeterRegistry meterRegistry, String clientName, String status, String outcome) {
        meterRegistry.counter(
            "aggregation.downstream.requests",
            "client", clientName,
            "status", status,
            "outcome", outcome
        ).increment();
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

    @FunctionalInterface
    private interface MetricRecorder {
        void capture(String status, String outcome);
    }
}
