package dev.abramenka.aggregation.error;

import java.util.List;

public final class UnsupportedAggregationPartException extends FacadeException {

    private static final String POINTER = "/request/include";

    public UnsupportedAggregationPartException() {
        super(
                ProblemCatalog.CLIENT_VALIDATION,
                null,
                List.of(new ValidationError(POINTER, "contains unsupported aggregation part")),
                null);
    }
}
