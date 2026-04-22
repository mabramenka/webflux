package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.DownstreamClientResponses;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartPlan;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.part.AggregationPartExecutor;
import dev.abramenka.aggregation.part.AggregationPartPlanner;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AggregateService {

    private static final String ACCOUNT_GROUP_CLIENT_NAME =
            HttpServiceGroups.downstreamClientName(HttpServiceGroups.ACCOUNT_GROUP);
    private static final String IDS_FIELD = "ids";

    private final AccountGroups accountGroupClient;
    private final AggregationPartPlanner partPlanner;
    private final AggregationPartExecutor partExecutor;
    private final ObservationRegistry observationRegistry;
    private final ObjectMapper objectMapper;

    public AggregateService(
            AccountGroups accountGroupClient,
            AggregationPartPlanner partPlanner,
            AggregationPartExecutor partExecutor,
            ObservationRegistry observationRegistry,
            ObjectMapper objectMapper) {
        this.accountGroupClient = accountGroupClient;
        this.partPlanner = partPlanner;
        this.partExecutor = partExecutor;
        this.observationRegistry = observationRegistry;
        this.objectMapper = objectMapper;
    }

    public Mono<JsonNode> aggregate(AggregateRequest request, ClientRequestContext clientRequestContext) {
        return Mono.defer(() -> {
            Observation observation = Observation.start("aggregation.request", observationRegistry);
            AggregationPartPlan partPlan = partPlanner.plan(request.include());
            observation.lowCardinalityKeyValue(
                    "part_selection", partPlan.requestedSelection().all() ? "all" : "subset");
            observation.lowCardinalityKeyValue(
                    "requested_parts",
                    Integer.toString(partPlan.requestedSelection().names().size()));

            ObjectNode accountGroupRequest;
            try {
                accountGroupRequest = toAccountGroupRequest(request.ids());
            } catch (Exception ex) {
                throw OrchestrationException.mappingFailed(ex);
            }

            return DownstreamClientResponses.requireBody(
                            ACCOUNT_GROUP_CLIENT_NAME,
                            accountGroupClient.fetchAccountGroup(accountGroupRequest, clientRequestContext))
                    .flatMap(accountGroupResponse -> {
                        AggregationContext context = new AggregationContext(
                                accountGroupResponse, clientRequestContext, partPlan.effectiveSelection());
                        return partExecutor.execute(ACCOUNT_GROUP_CLIENT_NAME, accountGroupResponse, context, partPlan);
                    })
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                    .doOnError(observation::error)
                    .doFinally(signalType -> observation.stop());
        });
    }

    private ObjectNode toAccountGroupRequest(List<String> ids) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode idsArray = request.putArray(IDS_FIELD);
        ids.forEach(id -> idsArray.add(id.toUpperCase(Locale.ROOT)));
        return request;
    }
}
