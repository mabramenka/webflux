package com.example.aggregation.api;

import com.example.aggregation.client.ClientRequestContext;
import com.example.aggregation.client.ForwardedHeaders;
import com.example.aggregation.error.InvalidAggregationRequestException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class ClientRequestContextFactory {

    public ClientRequestContext from(HttpHeaders headers, MultiValueMap<String, String> queryParams) {
        return new ClientRequestContext(
                forwardedHeaders(headers),
                booleanQueryParam(queryParams, ClientRequestContext.DETOKENIZE_QUERY_PARAM)
                        .orElse(null));
    }

    private static ForwardedHeaders forwardedHeaders(HttpHeaders inbound) {
        return ForwardedHeaders.builder()
                .authorization(inbound.getFirst(HttpHeaders.AUTHORIZATION))
                .requestId(inbound.getFirst(ForwardedHeaders.REQUEST_ID_HEADER))
                .correlationId(inbound.getFirst(ForwardedHeaders.CORRELATION_ID_HEADER))
                .acceptLanguage(inbound.getFirst(HttpHeaders.ACCEPT_LANGUAGE))
                .build();
    }

    private static Optional<Boolean> booleanQueryParam(MultiValueMap<String, String> queryParams, String name) {
        String rawValue = queryParams.getFirst(name);
        if (rawValue == null) {
            return Optional.empty();
        }
        if ("true".equalsIgnoreCase(rawValue)) {
            return Optional.of(true);
        }
        if ("false".equalsIgnoreCase(rawValue)) {
            return Optional.of(false);
        }
        throw new InvalidAggregationRequestException("'" + name + "' must be either true or false");
    }
}
