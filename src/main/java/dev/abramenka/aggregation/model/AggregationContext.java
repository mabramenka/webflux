package dev.abramenka.aggregation.model;

import dev.abramenka.aggregation.api.AggregateRequest;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

public final class AggregationContext {

    private final ObjectNode accountGroupResponse;
    private final ClientRequestContext clientRequestContext;
    private final @Nullable AggregateRequest aggregateRequest;

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
        AggregateRequest request = aggregateRequest;
        if (request == null) {
            return Optional.empty();
        }
        return Optional.of(request);
    }
}
