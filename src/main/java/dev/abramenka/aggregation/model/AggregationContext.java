package dev.abramenka.aggregation.model;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

public final class AggregationContext {

    private final JsonNode accountGroupResponse;
    private final ClientRequestContext clientRequestContext;
    private final EnrichmentSelection enrichmentSelection;
    private final ConcurrentMap<Object, Object> memo = new ConcurrentHashMap<>();

    public AggregationContext(
            JsonNode accountGroupResponse,
            ClientRequestContext clientRequestContext,
            EnrichmentSelection enrichmentSelection) {
        this.accountGroupResponse = Objects.requireNonNull(accountGroupResponse, "accountGroupResponse");
        this.clientRequestContext = Objects.requireNonNull(clientRequestContext, "clientRequestContext");
        this.enrichmentSelection = Objects.requireNonNull(enrichmentSelection, "enrichmentSelection");
    }

    public JsonNode accountGroupResponse() {
        return accountGroupResponse;
    }

    public ClientRequestContext clientRequestContext() {
        return clientRequestContext;
    }

    public EnrichmentSelection enrichmentSelection() {
        return enrichmentSelection;
    }

    public <T> T memoize(Object key, Function<JsonNode, T> compute) {
        @SuppressWarnings("unchecked")
        T value = (T) memo.computeIfAbsent(key, k -> compute.apply(accountGroupResponse));
        return value;
    }
}
