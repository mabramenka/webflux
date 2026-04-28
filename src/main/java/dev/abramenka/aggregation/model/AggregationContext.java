package dev.abramenka.aggregation.model;

import dev.abramenka.aggregation.api.AggregateRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

public final class AggregationContext {

    private final ObjectNode accountGroupResponse;
    private final ClientRequestContext clientRequestContext;
    private final @Nullable AggregateRequest aggregateRequest;
    private final ConcurrentMap<Object, Object> memo = new ConcurrentHashMap<>();

    public AggregationContext(ObjectNode accountGroupResponse, ClientRequestContext clientRequestContext) {
        this(accountGroupResponse, clientRequestContext, null);
    }

    public AggregationContext(
            ObjectNode accountGroupResponse,
            ClientRequestContext clientRequestContext,
            @Nullable AggregateRequest aggregateRequest) {
        this.accountGroupResponse = Objects.requireNonNull(accountGroupResponse, "accountGroupResponse");
        this.clientRequestContext = Objects.requireNonNull(clientRequestContext, "clientRequestContext");
        this.aggregateRequest = aggregateRequest;
    }

    public ObjectNode accountGroupResponse() {
        return accountGroupResponse;
    }

    public ClientRequestContext clientRequestContext() {
        return clientRequestContext;
    }

    public Optional<AggregateRequest> aggregateRequest() {
        return Optional.ofNullable(aggregateRequest);
    }

    public <T> T memoize(Object key, Function<ObjectNode, T> compute) {
        @SuppressWarnings("unchecked")
        T value = (T) memo.computeIfAbsent(key, k -> compute.apply(accountGroupResponse));
        return value;
    }
}
