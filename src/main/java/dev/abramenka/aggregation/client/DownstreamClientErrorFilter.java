package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.error.DownstreamClientException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@UtilityClass
@Slf4j
public final class DownstreamClientErrorFilter {

    private static final int MAX_RETRY_ATTEMPTS = 1;
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofMillis(200);
    private static final double RETRY_JITTER = 0.5;

    public static ExchangeFilterFunction forClient(String clientName) {
        String defaultMessage = "%s client request failed".formatted(clientName.toLowerCase(Locale.ROOT));
        return (request, next) -> next.exchange(request)
                .retryWhen(Retry.backoff(MAX_RETRY_ATTEMPTS, RETRY_MIN_BACKOFF)
                        .jitter(RETRY_JITTER)
                        .filter(DownstreamClientErrorFilter::isTransient)
                        .doBeforeRetry(signal -> log.warn(
                                "Retrying {} client after transient failure (attempt {})",
                                clientName,
                                signal.totalRetries() + 1,
                                signal.failure()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorMap(
                        ex -> !(ex instanceof DownstreamClientException),
                        ex -> DownstreamClientException.transportError(clientName, defaultMessage, ex))
                .flatMap(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .onErrorResume(ex -> response.releaseBody().then(Mono.empty()))
                                .filter(message -> !message.isBlank())
                                .defaultIfEmpty(defaultMessage)
                                .flatMap(message -> Mono.error(
                                        new DownstreamClientException(clientName, response.statusCode(), message)));
                    }
                    return Mono.just(response);
                });
    }

    private static boolean isTransient(Throwable ex) {
        return ex instanceof WebClientRequestException || ex instanceof TimeoutException;
    }
}
