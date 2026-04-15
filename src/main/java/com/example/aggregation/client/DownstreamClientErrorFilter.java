package com.example.aggregation.client;

import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

public final class DownstreamClientErrorFilter {

    private DownstreamClientErrorFilter() {
    }

    public static ExchangeFilterFunction forClient(String clientName) {
        return (request, next) -> next.exchange(request)
            .flatMap(response -> {
                if (!response.statusCode().isError()) {
                    return Mono.just(response);
                }
                return response.bodyToMono(String.class)
                    .filter(message -> !message.isBlank())
                    .defaultIfEmpty(defaultErrorMessage(clientName))
                    .flatMap(message -> Mono.error(new IllegalStateException(clientName + " client failed: " + message)));
            });
    }

    private static String defaultErrorMessage(String clientName) {
        return clientName.substring(0, 1).toLowerCase() + clientName.substring(1) + " client request failed";
    }
}
