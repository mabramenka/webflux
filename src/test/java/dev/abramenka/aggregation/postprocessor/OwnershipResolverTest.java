package dev.abramenka.aggregation.postprocessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abramenka.aggregation.client.Owners;
import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPartSelection;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class OwnershipResolverTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private Owners ownersClient;

    @Test
    void resolveTree_collectsIndividualsAcrossLevelsInInsertionOrder() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        JsonNode root = entity("R", List.of("A", "B"), List.of());
        stubLevels(Map.of(
                Set.of("A", "B"), respond(individual("A"), entity("B", List.of("C"), List.of("D"))),
                Set.of("C", "D"), respond(individual("C"), individual("D"))));

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .assertNext(arr -> assertThat(numbers(arr)).containsExactly("A", "C", "D"))
                .verifyComplete();
    }

    @Test
    void resolveTree_deduplicatesSameNumberAcrossLevels() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        JsonNode root = entity("R", List.of("A", "B"), List.of());
        stubLevels(Map.of(
                Set.of("A", "B"), respond(individual("A"), entity("B", List.of("A"), List.of("C"))),
                Set.of("C"), respond(individual("C"))));

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .assertNext(arr -> assertThat(numbers(arr)).containsExactly("A", "C"))
                .verifyComplete();

        ArgumentCaptor<ObjectNode> requestCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(ownersClient, times(2)).fetchOwners(requestCaptor.capture(), any(ClientRequestContext.class));
        List<List<String>> idsPerCall = requestCaptor.getAllValues().stream()
                .map(req -> req.path("ids").values().stream()
                        .map(JsonNode::asString)
                        .toList())
                .toList();
        assertThat(idsPerCall.get(0)).containsExactlyInAnyOrder("A", "B");
        assertThat(idsPerCall.get(1)).containsExactly("C");
    }

    @Test
    void resolveTree_handlesCycleWithoutInfiniteLoop() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        JsonNode root = entity("R", List.of("A"), List.of());
        stubLevels(Map.of(
                Set.of("A"), respond(entity("A", List.of("B"), List.of())),
                Set.of("B"), respond(entity("B", List.of("A"), List.of()))));

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .assertNext(arr -> assertThat(arr.size()).isZero())
                .verifyComplete();

        verify(ownersClient, times(2)).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    @Test
    void resolveTree_allowsSixHops() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        JsonNode root = entity("R", List.of("L1"), List.of());
        Map<Set<String>, JsonNode> levels = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            levels.put(Set.of("L" + i), respond(entity("L" + i, List.of("L" + (i + 1)), List.of())));
        }
        levels.put(Set.of("L6"), respond(individual("L6")));
        stubLevels(levels);

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .assertNext(arr -> assertThat(numbers(arr)).containsExactly("L6"))
                .verifyComplete();
    }

    @Test
    void resolveTree_failsWhenDepthExceedsSix() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        JsonNode root = entity("R", List.of("L1"), List.of());
        Map<Set<String>, JsonNode> levels = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            levels.put(Set.of("L" + i), respond(entity("L" + i, List.of("L" + (i + 1)), List.of())));
        }
        stubLevels(levels);

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(BeneficialOwnersResolutionException.class);
                    assertThat(((BeneficialOwnersResolutionException) ex).reason())
                            .isEqualTo(BeneficialOwnersResolutionException.Reason.DEPTH_EXCEEDED);
                })
                .verify();
    }

    @Test
    void resolveTree_failsTreeWhenDownstreamErrors() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        JsonNode root = entity("R", List.of("A"), List.of());
        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenReturn(Mono.error(new RuntimeException("owners 5xx")));

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(BeneficialOwnersResolutionException.class);
                    assertThat(((BeneficialOwnersResolutionException) ex).reason())
                            .isEqualTo(BeneficialOwnersResolutionException.Reason.DOWNSTREAM_FAILED);
                })
                .verify();
    }

    @Test
    void resolveTree_skipsMissingMembersAndReturnsCollectedIndividuals() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        JsonNode root = objectMapper
                .createObjectNode()
                .set(
                        "entity",
                        objectMapper
                                .createObjectNode()
                                .put("number", "R")
                                .set("ownershipStructure", malformedOwnershipStructure("A")));
        stubLevels(Map.of(Set.of("A"), respond(individual("A"))));

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .assertNext(arr -> assertThat(numbers(arr)).containsExactly("A"))
                .verifyComplete();
    }

    @Test
    void resolveTree_returnsEmptyArrayWhenEntityHasNoOwnershipStructure() {
        OwnershipResolver resolver = new OwnershipResolver(ownersClient, objectMapper);
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("entity").put("number", "R");

        StepVerifier.create(resolver.resolveTree(root, aggregationContext()))
                .assertNext(arr -> assertThat(arr.size()).isZero())
                .verifyComplete();

        verify(ownersClient, never()).fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class));
    }

    private void stubLevels(Map<Set<String>, JsonNode> levelsByIds) {
        when(ownersClient.fetchOwners(any(ObjectNode.class), any(ClientRequestContext.class)))
                .thenAnswer(invocation -> {
                    ObjectNode request = invocation.getArgument(0);
                    Set<String> ids = new HashSet<>();
                    request.path("ids").values().forEach(node -> ids.add(node.asString()));
                    JsonNode response = levelsByIds.get(ids);
                    if (response == null) {
                        throw new AssertionError(
                                "Unexpected level ids: " + ids + "; expected one of " + levelsByIds.keySet());
                    }
                    return Mono.just(response);
                });
    }

    private JsonNode respond(JsonNode... items) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode data = response.putArray("data");
        Stream.of(items).forEach(data::add);
        return response;
    }

    private JsonNode individual(String number) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("individual").put("number", number);
        return node;
    }

    private JsonNode entity(String number, List<String> principalNumbers, List<String> indirectNumbers) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode entity = node.putObject("entity");
        entity.put("number", number);
        ArrayNode structures = entity.putArray("ownershipStructure");
        ObjectNode structure = structures.addObject();
        ArrayNode principals = structure.putArray("principalOwners");
        principalNumbers.forEach(
                num -> principals.addObject().putObject("memberDetails").put("number", num));
        ArrayNode indirects = structure.putArray("indirectOwners");
        indirectNumbers.forEach(
                num -> indirects.addObject().putObject("memberDetails").put("number", num));
        return node;
    }

    private ArrayNode malformedOwnershipStructure(String validNumber) {
        ArrayNode structures = objectMapper.createArrayNode();
        ObjectNode structure = structures.addObject();
        ArrayNode principals = structure.putArray("principalOwners");
        principals.addObject();
        principals.addObject().putObject("memberDetails");
        principals.addObject().putObject("memberDetails").put("number", "  ");
        principals.addObject().putObject("memberDetails").put("number", validNumber);
        return structures;
    }

    private List<String> numbers(ArrayNode array) {
        List<String> numbers = new ArrayList<>();
        array.values().forEach(node -> numbers.add(EntityNumbersExtractor.ownerNumber(node)));
        return numbers;
    }

    private AggregationContext aggregationContext() {
        ClientRequestContext clientRequestContext =
                new ClientRequestContext(ForwardedHeaders.builder().build(), null);
        ObjectNode accountGroupResponse = objectMapper.createObjectNode();
        return new AggregationContext(accountGroupResponse, clientRequestContext, AggregationPartSelection.from(null));
    }
}
