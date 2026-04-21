package dev.abramenka.aggregation.config;

import dev.abramenka.aggregation.error.RequestValidationException;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class ClientRequestContextFactory {

    public ClientRequestContext from(HttpHeaders headers, MultiValueMap<String, String> queryParams) {
        return new ClientRequestContext(
                forwardedHeaders(headers), booleanQueryParam(queryParams, ClientRequestContext.DETOKENIZE_QUERY_PARAM));
    }

    private static ForwardedHeaders forwardedHeaders(HttpHeaders inbound) {
        return ForwardedHeaders.builder()
                .authorization(inbound.getFirst(HttpHeaders.AUTHORIZATION))
                .requestId(inbound.getFirst(ForwardedHeaders.REQUEST_ID_HEADER))
                .correlationId(inbound.getFirst(ForwardedHeaders.CORRELATION_ID_HEADER))
                .acceptLanguage(inbound.getFirst(HttpHeaders.ACCEPT_LANGUAGE))
                .build();
    }

    private static @Nullable Boolean booleanQueryParam(MultiValueMap<String, String> queryParams, String name) {
        String rawValue = queryParams.getFirst(name);
        if (rawValue == null) {
            return null;
        }
        return switch (rawValue.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default ->
                throw RequestValidationException.invalidQueryParameter(
                        name, "'" + name + "' must be either true or false");
        };
    }
}
