package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.error.DownstreamClientException;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

@UtilityClass
public final class DownstreamClientErrorFilter {
    public static ExchangeFilterFunction forClient(String clientName) {
        return (request, next) -> next.exchange(request)
                .onErrorMap(ex -> DownstreamClientException.transportError(
                        clientName, "%s client request failed".formatted(clientName.toLowerCase(Locale.ROOT)), ex))
                .flatMap(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .filter(message -> !message.isBlank())
                                .defaultIfEmpty(
                                        "%s client request failed".formatted(clientName.toLowerCase(Locale.ROOT)))
                                .flatMap(message -> Mono.error(
                                        new DownstreamClientException(clientName, response.statusCode(), message)));
                    }
                    return Mono.just(response);
                });
    }
}
