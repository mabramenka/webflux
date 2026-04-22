package dev.abramenka.aggregation.model;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

public final class AggregationContext {

    private final JsonNode accountGroupResponse;
    private final ClientRequestContext clientRequestContext;
    private final AggregationPartSelection partSelection;
    private final ConcurrentMap<Object, Object> memo = new ConcurrentHashMap<>();

    public AggregationContext(
            JsonNode accountGroupResponse,
            ClientRequestContext clientRequestContext,
            AggregationPartSelection partSelection) {
        this.accountGroupResponse = Objects.requireNonNull(accountGroupResponse, "accountGroupResponse");
        this.clientRequestContext = Objects.requireNonNull(clientRequestContext, "clientRequestContext");
        this.partSelection = Objects.requireNonNull(partSelection, "partSelection");
    }

    public JsonNode accountGroupResponse() {
        return accountGroupResponse;
    }

    public ClientRequestContext clientRequestContext() {
        return clientRequestContext;
    }

    public AggregationPartSelection partSelection() {
        return partSelection;
    }

    public AggregationContext withAccountGroupResponse(JsonNode accountGroupResponse) {
        return new AggregationContext(accountGroupResponse, clientRequestContext, partSelection);
    }

    public <T> T memoize(Object key, Function<JsonNode, T> compute) {
        @SuppressWarnings("unchecked")
        T value = (T) memo.computeIfAbsent(key, k -> compute.apply(accountGroupResponse));
        return value;
    }
}
