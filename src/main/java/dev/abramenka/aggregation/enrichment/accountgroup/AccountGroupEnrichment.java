package dev.abramenka.aggregation.enrichment.accountgroup;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.DownstreamClientResponses;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class AccountGroupEnrichment implements AggregationPart {

    static final String NAME = "accountGroup";
    private static final String CLIENT_NAME = HttpServiceGroups.downstreamClientName(HttpServiceGroups.ACCOUNT_GROUP);
    private static final String IDS_FIELD = "ids";

    private final AccountGroups accountGroups;
    private final ObjectMapper objectMapper;

    AccountGroupEnrichment(AccountGroups accountGroups, ObjectMapper objectMapper) {
        this.accountGroups = accountGroups;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean base() {
        return true;
    }

    @Override
    public boolean publicSelectable() {
        return false;
    }

    @Override
    public Mono<AggregationPartResult> execute(AggregationContext context) {
        return Mono.defer(() -> {
            List<String> ids = context.aggregateRequest()
                    .map(AggregateRequest::ids)
                    .orElseThrow(() -> OrchestrationException.invariantViolated(
                            new IllegalStateException("Aggregate request is required for accountGroup part")));
            ObjectNode request = toAccountGroupRequest(ids);
            String fields = context.clientRequestContext().projections().orDefault(AccountGroups.DEFAULT_FIELDS);
            return DownstreamClientResponses.requireBody(
                            CLIENT_NAME,
                            accountGroups.fetchAccountGroup(request, fields, context.clientRequestContext()))
                    .map(response -> toReplacement(response, name()));
        });
    }

    private ObjectNode toAccountGroupRequest(List<String> ids) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode idsArray = request.putArray(IDS_FIELD);
        ids.forEach(id -> idsArray.add(id.toUpperCase(Locale.ROOT)));
        return request;
    }

    private static AggregationPartResult toReplacement(JsonNode response, String partName) {
        if (!response.isObject()) {
            throw DownstreamClientException.contractViolation(CLIENT_NAME);
        }
        return AggregationPartResult.replacement(partName, (ObjectNode) response);
    }
}
