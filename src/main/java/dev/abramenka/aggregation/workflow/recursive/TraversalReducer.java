package dev.abramenka.aggregation.workflow.recursive;

import dev.abramenka.aggregation.patch.JsonPatchDocument;
import tools.jackson.databind.JsonNode;

/** Reduces a traversal STEP_RESULT payload into a write patch. */
@FunctionalInterface
public interface TraversalReducer {

    JsonPatchDocument reduce(JsonNode traversalResult, JsonNode rootForWriteDecision);
}
