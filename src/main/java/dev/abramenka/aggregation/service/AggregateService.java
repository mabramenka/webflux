package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.api.AggregateRequest;
import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.enrichment.AggregationEnrichment;
import dev.abramenka.aggregation.error.DownstreamClientException;
import dev.abramenka.aggregation.error.InvalidAggregationRequestException;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.EnrichmentSelection;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AggregateService {

    private static final String ACCOUNT_GROUP_CLIENT_NAME = "Account group";
    private static final String IDS_FIELD = "ids";

    private final AccountGroups accountGroupClient;
    private final List<AggregationEnrichment> enrichments;
    private final Map<String, AggregationEnrichment> enrichmentByName;
    private final ObservationRegistry observationRegistry;
    private final EnrichmentExecutor enrichmentExecutor;
    private final AggregationMerger aggregationMerger;
    private final ObjectMapper objectMapper;

    public AggregateService(
            AccountGroups accountGroupClient,
            List<AggregationEnrichment> enrichments,
            ObservationRegistry observationRegistry,
            EnrichmentExecutor enrichmentExecutor,
            AggregationMerger aggregationMerger,
            ObjectMapper objectMapper) {
        this.accountGroupClient = accountGroupClient;
        this.enrichments = List.copyOf(enrichments);
        this.enrichmentByName = buildEnrichmentIndex(enrichments);
        this.observationRegistry = observationRegistry;
        this.enrichmentExecutor = enrichmentExecutor;
        this.aggregationMerger = aggregationMerger;
        this.objectMapper = objectMapper;
    }

    public Mono<JsonNode> aggregate(AggregateRequest request, ClientRequestContext clientRequestContext) {
        return Mono.defer(() -> {
            Observation observation = Observation.start("aggregation.request", observationRegistry);
            EnrichmentSelection enrichmentSelection = EnrichmentSelection.from(request.include());
            validateEnrichmentSelection(enrichmentSelection);
            observation.lowCardinalityKeyValue("enrichment_selection", enrichmentSelection.all() ? "all" : "subset");
            observation.lowCardinalityKeyValue(
                    "requested_enrichments",
                    Integer.toString(enrichmentSelection.names().size()));

            ObjectNode accountGroupRequest = toAccountGroupRequest(request.ids());

            return fetchAccountGroup(accountGroupRequest, clientRequestContext)
                    .flatMap(accountGroupResponse -> {
                        AggregationContext context =
                                new AggregationContext(accountGroupResponse, clientRequestContext, enrichmentSelection);

                        List<AggregationEnrichment> enabledEnrichments = enrichments.stream()
                                .filter(enrichment -> enrichmentSelection.includes(enrichment.name()))
                                .filter(enrichment -> enrichment.supports(context))
                                .toList();

                        ObjectNode root =
                                aggregationMerger.mutableRoot(ACCOUNT_GROUP_CLIENT_NAME, accountGroupResponse);

                        int concurrency = Math.max(1, enabledEnrichments.size());
                        return Flux.fromIterable(enabledEnrichments)
                                .flatMap(enrichment -> enrichmentExecutor.fetch(enrichment, context), concurrency)
                                .collectList()
                                .map(results -> aggregationMerger.merge(root, enabledEnrichments, results));
                    })
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                    .doOnError(observation::error)
                    .doFinally(signalType -> observation.stop());
        });
    }

    private Mono<JsonNode> fetchAccountGroup(
            ObjectNode accountGroupRequest, ClientRequestContext clientRequestContext) {
        return accountGroupClient
                .fetchAccountGroup(accountGroupRequest, clientRequestContext)
                .onErrorMap(
                        ex -> !(ex instanceof DownstreamClientException),
                        ex -> DownstreamClientException.gatewayError(
                                ACCOUNT_GROUP_CLIENT_NAME, "account group client returned an unreadable response", ex));
    }

    private ObjectNode toAccountGroupRequest(List<String> ids) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode idsArray = request.putArray(IDS_FIELD);
        ids.forEach(idsArray::add);
        return request;
    }

    private void validateEnrichmentSelection(EnrichmentSelection enrichmentSelection) {
        if (enrichmentSelection.all()) {
            return;
        }

        List<String> unknownEnrichments = enrichmentSelection.names().stream()
                .filter(name -> !enrichmentByName.containsKey(name))
                .toList();

        if (!unknownEnrichments.isEmpty()) {
            throw new InvalidAggregationRequestException(
                    "Unknown aggregation enrichment(s): " + String.join(", ", unknownEnrichments));
        }
    }

    private static Map<String, AggregationEnrichment> buildEnrichmentIndex(
            List<AggregationEnrichment> registeredEnrichments) {
        Map<String, AggregationEnrichment> index = new LinkedHashMap<>();
        registeredEnrichments.forEach(enrichment -> {
            AggregationEnrichment previous = index.putIfAbsent(enrichment.name(), enrichment);
            if (previous != null) {
                throw new IllegalStateException("Duplicate aggregation enrichment name: " + enrichment.name());
            }
        });
        return Map.copyOf(index);
    }
}
