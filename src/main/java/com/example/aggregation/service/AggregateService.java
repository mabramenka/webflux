package com.example.aggregation.service;

import com.example.aggregation.enrichment.AggregationEnrichment;
import com.example.aggregation.client.ClientRequestContext;
import com.example.aggregation.client.DownstreamClientException;
import com.example.aggregation.error.InvalidAggregationRequestException;
import com.example.aggregation.client.AccountGroups;
import com.example.aggregation.model.AggregationContext;
import com.example.aggregation.model.EnrichmentFetchResult;
import com.example.aggregation.model.EnrichmentSelection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@Slf4j
public class AggregateService {

    private static final String CUSTOMER_ID_FIELD = "customerId";
    private static final String ACCOUNT_GROUP_CLIENT_NAME = "Account group";

    private final AccountGroups accountGroupClient;
    private final List<AggregationEnrichment> enrichments;
    private final Map<String, AggregationEnrichment> enrichmentByName;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public AggregateService(
        AccountGroups accountGroupClient,
        List<AggregationEnrichment> enrichments,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry
    ) {
        this.accountGroupClient = accountGroupClient;
        this.enrichments = List.copyOf(enrichments);
        this.enrichmentByName = buildEnrichmentIndex(enrichments);
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
    }

    public Mono<JsonNode> aggregate(ObjectNode inboundRequest, ClientRequestContext clientRequestContext) {
        return Mono.defer(() -> {
            Observation observation = Observation.start("aggregation.request", observationRegistry);
            EnrichmentSelection enrichmentSelection = EnrichmentSelection.from(inboundRequest);
            validateEnrichmentSelection(enrichmentSelection);
            observation.lowCardinalityKeyValue("enrichment_selection", enrichmentSelection.all() ? "all" : "subset");
            observation.lowCardinalityKeyValue("requested_enrichments", Integer.toString(enrichmentSelection.names().size()));

            ObjectNode accountGroupRequest = buildAccountGroupRequest(inboundRequest);

            return fetchAccountGroup(accountGroupRequest, clientRequestContext)
                .flatMap(accountGroupResponse -> {
                    AggregationContext context = new AggregationContext(
                        inboundRequest,
                        accountGroupResponse,
                        clientRequestContext,
                        enrichmentSelection
                    );

                    List<AggregationEnrichment> enabledEnrichments = enrichments.stream()
                        .filter(enrichment -> enrichmentSelection.includes(enrichment.name()))
                        .filter(enrichment -> enrichment.supports(context))
                        .toList();

                    ObjectNode root = mutableAccountGroupResponse(accountGroupResponse);

                    return Flux.fromIterable(enabledEnrichments)
                        .flatMap(enrichment -> fetchEnrichment(enrichment, context))
                        .collectList()
                        .map(results -> merge(root, enabledEnrichments, results));
                })
                .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                .doOnError(observation::error)
                .doFinally(signalType -> observation.stop());
        });
    }

    private Mono<JsonNode> fetchAccountGroup(ObjectNode accountGroupRequest, ClientRequestContext clientRequestContext) {
        return accountGroupClient.fetchAccountGroup(accountGroupRequest, clientRequestContext)
            .onErrorMap(
                ex -> !(ex instanceof DownstreamClientException),
                ex -> new DownstreamClientException(
                    ACCOUNT_GROUP_CLIENT_NAME,
                    HttpStatus.BAD_GATEWAY,
                    "account group client returned an unreadable response",
                    ex
                )
            );
    }

    private ObjectNode mutableAccountGroupResponse(JsonNode accountGroupResponse) {
        if (!accountGroupResponse.isObject()) {
            throw new DownstreamClientException(
                ACCOUNT_GROUP_CLIENT_NAME,
                HttpStatus.BAD_GATEWAY,
                "account group client returned a non-object JSON response"
            );
        }
        return (ObjectNode) accountGroupResponse.deepCopy();
    }

    private ObjectNode buildAccountGroupRequest(ObjectNode inboundRequest) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put(CUSTOMER_ID_FIELD, requiredString(inboundRequest, CUSTOMER_ID_FIELD));
        request.put("market", optionalString(inboundRequest, "market", "US"));
        request.put("includeItems", optionalBoolean(inboundRequest, "includeItems", true));
        return request;
    }

    private String requiredString(ObjectNode request, String fieldName) {
        JsonNode field = request.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            throw new InvalidAggregationRequestException("'" + fieldName + "' is required");
        }
        return stringValue(field)
            .orElseThrow(() -> new InvalidAggregationRequestException("'" + fieldName + "' must be a non-blank string"));
    }

    private String optionalString(ObjectNode request, String fieldName, String defaultValue) {
        JsonNode field = request.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return defaultValue;
        }
        return stringValue(field)
            .orElseThrow(() -> new InvalidAggregationRequestException("'" + fieldName + "' must be a non-blank string"));
    }

    private Optional<String> stringValue(JsonNode field) {
        return field.stringValueOpt()
            .map(String::trim)
            .filter(value -> !value.isBlank());
    }

    private boolean optionalBoolean(ObjectNode request, String fieldName, boolean defaultValue) {
        JsonNode field = request.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return defaultValue;
        }
        return field.booleanValueOpt()
            .orElseThrow(() -> new InvalidAggregationRequestException("'" + fieldName + "' must be a boolean"));
    }

    private Mono<EnrichmentFetchResult> fetchEnrichment(AggregationEnrichment enrichment, AggregationContext context) {
        return enrichment.fetch(context)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                "Optional aggregation enrichment '" + enrichment.name() + "' returned an empty response"
            )))
            .doOnSuccess(response -> recordEnrichmentFetch(enrichment.name(), "SUCCESS"))
            .map(response -> EnrichmentFetchResult.success(enrichment, response))
            .onErrorResume(ex -> {
                recordEnrichmentFetch(enrichment.name(), "ERROR");
                log.warn("Optional aggregation enrichment '{}' failed and will be skipped", enrichment.name(), ex);
                return Mono.just(EnrichmentFetchResult.failed(enrichment, ex));
            });
    }

    private void recordEnrichmentFetch(String enrichmentName, String outcome) {
        meterRegistry.counter(
            "aggregation.enrichment.requests",
            "enrichment", enrichmentName,
            "outcome", outcome
        ).increment();
    }

    private JsonNode merge(ObjectNode root, List<AggregationEnrichment> enabledEnrichments, List<EnrichmentFetchResult> results) {
        Map<String, EnrichmentFetchResult> resultByName = results.stream()
            .collect(Collectors.toMap(EnrichmentFetchResult::name, Function.identity()));

        enabledEnrichments.stream()
            .map(enrichment -> resultByName.get(enrichment.name()))
            .filter(result -> result != null && result.successful())
            .forEach(result -> result.mergeInto(root));

        return root;
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
                "Unknown aggregation enrichment(s): " + String.join(", ", unknownEnrichments)
            );
        }
    }

    private Map<String, AggregationEnrichment> buildEnrichmentIndex(List<AggregationEnrichment> registeredEnrichments) {
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
