package dev.abramenka.aggregation.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.error.OrchestrationException;
import dev.abramenka.aggregation.patch.JsonPatchOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class WorkflowPatchConflictDetectorTest {

    private final JsonNodeFactory nf = JsonMapper.builder().build().getNodeFactory();
    private WorkflowPatchConflictDetector detector;

    @BeforeEach
    void setUp() {
        detector = new WorkflowPatchConflictDetector();
    }

    // -------------------------------------------------------------------------
    // First write always allowed
    // -------------------------------------------------------------------------

    @Test
    void firstWrite_isAlwaysAllowed() {
        assertThatCode(() -> detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("x"))))
                .doesNotThrowAnyException();
    }

    @Test
    void differentPaths_areIndependent() {
        detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("x")));
        assertThatCode(() -> detector.check(new JsonPatchOperation.Add("/b", nf.stringNode("y"))))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Idempotent — same path, same op type, same value
    // -------------------------------------------------------------------------

    @Test
    void samePath_sameType_sameValue_isIdempotent() {
        detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("x")));
        assertThatCode(() -> detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("x"))))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Conflict — same path, different value
    // -------------------------------------------------------------------------

    @Test
    void samePath_sameType_differentValue_throws() {
        detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("x")));
        assertThatThrownBy(() -> detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("y"))))
                .isInstanceOf(OrchestrationException.class)
                .hasMessageContaining("assemble");
    }

    // -------------------------------------------------------------------------
    // Conflict — same path, different op type
    // -------------------------------------------------------------------------

    @Test
    void samePath_differentType_throws() {
        detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("x")));
        assertThatThrownBy(() -> detector.check(new JsonPatchOperation.Replace("/a", nf.stringNode("x"))))
                .isInstanceOf(OrchestrationException.class)
                .hasMessageContaining("assemble");
    }

    @Test
    void samePath_replaceFirst_thenAdd_throws() {
        detector.check(new JsonPatchOperation.Replace("/a", nf.stringNode("x")));
        assertThatThrownBy(() -> detector.check(new JsonPatchOperation.Add("/a", nf.stringNode("x"))))
                .isInstanceOf(OrchestrationException.class);
    }

    // -------------------------------------------------------------------------
    // Array appends — always permitted regardless of count
    // -------------------------------------------------------------------------

    @Test
    void appendPaths_multipleAllowed() {
        assertThatCode(() -> {
                    detector.check(new JsonPatchOperation.Add("/data/0/tags/-", nf.stringNode("a")));
                    detector.check(new JsonPatchOperation.Add("/data/0/tags/-", nf.stringNode("b")));
                    detector.check(new JsonPatchOperation.Add("/data/0/tags/-", nf.stringNode("c")));
                })
                .doesNotThrowAnyException();
    }

    @Test
    void appendPath_doesNotConflictWithNonAppendOnSameArrayPath() {
        // Creating the array at /data/0/tags and appending to /data/0/tags/- are distinct paths
        detector.check(new JsonPatchOperation.Add("/data/0/tags", nf.arrayNode()));
        assertThatCode(() -> detector.check(new JsonPatchOperation.Add("/data/0/tags/-", nf.stringNode("v"))))
                .doesNotThrowAnyException();
    }
}
