package dev.abramenka.aggregation.workflow.binding.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class TargetMatcherTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TargetMatcher matcher = new TargetMatcher();

    @Test
    void matchesByKeyAndDropsUnmatched() {
        ObjectNode ownerA = node("ownerA");
        ObjectNode ownerB = node("ownerB");
        ObjectNode ownerMissing = node("ownerMissing");

        Map<String, JsonNode> index = new LinkedHashMap<>();
        index.put("a", node("entryA"));
        index.put("b", node("entryB"));

        List<ExtractedTarget> targets = List.of(
                new ExtractedTarget("a", ownerA),
                new ExtractedTarget("missing", ownerMissing),
                new ExtractedTarget("b", ownerB));

        List<MatchedTarget> matches = matcher.match(targets, index);

        assertThat(matches).hasSize(2);
        assertThat(matches.getFirst().key()).isEqualTo("a");
        assertThat(matches.get(0).owner()).isSameAs(ownerA);
        assertThat(matches.get(0).responseEntry().path("tag").asString()).isEqualTo("entryA");
        assertThat(matches.get(1).owner()).isSameAs(ownerB);
    }

    @Test
    void emptyTargetsProduceEmptyMatches() {
        assertThat(matcher.match(List.of(), Map.of("a", node("entryA")))).isEmpty();
    }

    private ObjectNode node(String tag) {
        ObjectNode obj = mapper.createObjectNode();
        obj.put("tag", tag);
        return obj;
    }
}
