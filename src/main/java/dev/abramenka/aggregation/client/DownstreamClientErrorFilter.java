package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.error.DownstreamClientException;
import java.util.Locale;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

public final class DownstreamClientErrorFilter {

    private DownstreamClientErrorFilter() {}

    public static ExchangeFilterFunction forClient(String clientName) {
        return (request, next) -> next.exchange(request)
                .onErrorMap(
                        ex -> DownstreamClientException.transportError(clientName, defaultErrorMessage(clientName), ex))
                .flatMap(response -> {
                    if (!response.statusCode().isError()) {
                        return Mono.just(response);
                    }
                    var downstreamStatusCode = response.statusCode();
                    return response.bodyToMono(String.class)
                            .filter(message -> !message.isBlank())
                            .defaultIfEmpty(defaultErrorMessage(clientName))
                            .flatMap(message -> Mono.error(
                                    new DownstreamClientException(clientName, downstreamStatusCode, message)));
                });
    }

    private static String defaultErrorMessage(String clientName) {
        return clientName.substring(0, 1).toLowerCase(Locale.ROOT) + clientName.substring(1) + " client request failed";
    }
}
