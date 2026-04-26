package dev.abramenka.aggregation.enrichment.owners;

import dev.abramenka.aggregation.client.DownstreamClientResponses;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.model.PartCriticality;
import dev.abramenka.aggregation.workflow.AggregationWorkflow;
import dev.abramenka.aggregation.workflow.WorkflowAggregationPart;
import dev.abramenka.aggregation.workflow.WorkflowExecutor;
import dev.abramenka.aggregation.workflow.binding.BindingName;
import dev.abramenka.aggregation.workflow.binding.DownstreamBinding;
import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import dev.abramenka.aggregation.workflow.binding.ResponseIndexingRule;
import dev.abramenka.aggregation.workflow.binding.WriteRule;
import dev.abramenka.aggregation.workflow.ownership.WriteOwnership;
import dev.abramenka.aggregation.workflow.step.KeyedBindingStep;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class OwnersEnrichment extends WorkflowAggregationPart {

    private static final String CLIENT_NAME = HttpServiceGroups.downstreamClientName(HttpServiceGroups.OWNERS);

    OwnersEnrichment(Owners ownersClient, WorkflowExecutor workflowExecutor) {
        super(
                new AggregationWorkflow(
                        "owners",
                        Set.of(),
                        PartCriticality.REQUIRED,
                        List.of(new KeyedBindingStep(
                                "fetch",
                                new DownstreamBinding(
                                        new BindingName("owners"),
                                        new KeyExtractionRule(
                                                KeySource.ROOT_SNAPSHOT,
                                                null,
                                                "$.data[*]",
                                                List.of("basicDetails.owners[*].id", "basicDetails.owners[*].number")),
                                        (keys, ctx) -> {
                                            JsonNodeFactory nf = JsonNodeFactory.instance;
                                            ArrayNode idsArray = nf.arrayNode(keys.size());
                                            keys.forEach(idsArray::add);
                                            ObjectNode request = nf.objectNode();
                                            request.set("ids", idsArray);
                                            return DownstreamClientResponses.optionalBody(
                                                    CLIENT_NAME,
                                                    ownersClient.fetchOwners(
                                                            request,
                                                            Owners.DEFAULT_FIELDS,
                                                            ctx.clientRequestContext()));
                                        },
                                        new ResponseIndexingRule("$.data[*]", List.of("individual.number", "id")),
                                        null,
                                        new WriteRule(
                                                "$.data[*]",
                                                new WriteRule.MatchBy("basicDetails.owners[*].id", "individual.number"),
                                                new WriteRule.WriteAction.AppendToArray("owners1"))))),
                        WriteOwnership.of("owners1")),
                workflowExecutor);
    }
}
