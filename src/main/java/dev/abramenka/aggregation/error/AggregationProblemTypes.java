package dev.abramenka.aggregation.error;

import java.net.URI;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class AggregationProblemTypes {

    public static final URI INVALID_AGGREGATION_REQUEST = URI.create("/problems/invalid-aggregation-request");
    public static final URI DOWNSTREAM_CLIENT_ERROR = URI.create("/problems/downstream-client-error");
    public static final URI INTERNAL_AGGREGATION_ERROR = URI.create("/problems/internal-aggregation-error");
}
