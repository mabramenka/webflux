package dev.abramenka.aggregation.model;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import tools.jackson.databind.node.ObjectNode;

public final class AggregationContext {

    private final ObjectNode accountGroupResponse;
    private final ClientRequestContext clientRequestContext;
    private final ConcurrentMap<Object, Object> memo = new ConcurrentHashMap<>();

    public AggregationContext(ObjectNode accountGroupResponse, ClientRequestContext clientRequestContext) {
        this.accountGroupResponse = Objects.requireNonNull(accountGroupResponse, "accountGroupResponse");
        this.clientRequestContext = Objects.requireNonNull(clientRequestContext, "clientRequestContext");
    }

    public ObjectNode accountGroupResponse() {
        return accountGroupResponse;
    }

    public ClientRequestContext clientRequestContext() {
        return clientRequestContext;
    }

    public <T> T memoize(Object key, Function<ObjectNode, T> compute) {
        @SuppressWarnings("unchecked")
        T value = (T) memo.computeIfAbsent(key, k -> compute.apply(accountGroupResponse));
        return value;
    }
}
