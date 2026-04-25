package dev.abramenka.aggregation.workflow.binding.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.workflow.binding.KeyExtractionRule;
import dev.abramenka.aggregation.workflow.binding.KeySource;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class KeyExtractorTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final KeyExtractor extractor = new KeyExtractor();

    @Test
    void extractsKeysFromRootSnapshot() {
        JsonNode root = parse("""
                {"data": [
                  {"basicDetails": {"owners": [{"id": "o1"}, {"id": "o2"}]}},
                  {"basicDetails": {"owners": [{"id": "o3"}]}}
                ]}
                """);
        KeyExtractionRule rule =
                new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*].basicDetails.owners[*]", List.of("id"));

        List<ExtractedTarget> targets = extractor.extract(rule, root);

        assertThat(targets).extracting(ExtractedTarget::key).containsExactly("o1", "o2", "o3");
    }

    @Test
    void usesFallbackKeyPathWhenPrimaryMissing() {
        JsonNode root = parse("""
                {"data": [
                  {"basicDetails": {"owners": [{"id": "o1"}, {"number": "n2"}]}}
                ]}
                """);
        KeyExtractionRule rule = new KeyExtractionRule(
                KeySource.ROOT_SNAPSHOT, null, "$.data[*].basicDetails.owners[*]", List.of("id", "number"));

        List<ExtractedTarget> targets = extractor.extract(rule, root);

        assertThat(targets).extracting(ExtractedTarget::key).containsExactly("o1", "n2");
    }

    @Test
    void distinctKeysPreservesFirstSeenOrderAndDedups() {
        JsonNode root = parse("""
                {"data": [
                  {"id": "a"}, {"id": "b"}, {"id": "a"}, {"id": "c"}
                ]}
                """);
        KeyExtractionRule rule = new KeyExtractionRule(KeySource.ROOT_SNAPSHOT, null, "$.data[*]", List.of("id"));

        assertThat(extractor.distinctKeys(rule, root)).containsExactly("a", "b", "c");
    }

    @Test
    void rejectsNonRootSnapshotSource() {
        KeyExtractionRule rule = new KeyExtractionRule(KeySource.STEP_RESULT, "previous", "$.data[*]", List.of("id"));

        assertThatThrownBy(() -> extractor.extract(rule, mapper.createObjectNode()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ROOT_SNAPSHOT");
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
