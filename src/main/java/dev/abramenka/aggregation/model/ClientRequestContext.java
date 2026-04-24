package dev.abramenka.aggregation.model;

import org.jspecify.annotations.Nullable;

public record ClientRequestContext(
        ForwardedHeaders headers, @Nullable Boolean detokenize, Projections projections) {

    public static final String DETOKENIZE_QUERY_PARAM = "detokenize";
    public static final String FIELDS_QUERY_PARAM = "fields";
}
