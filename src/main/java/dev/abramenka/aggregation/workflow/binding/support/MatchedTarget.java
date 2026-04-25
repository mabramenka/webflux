package dev.abramenka.aggregation.workflow.binding.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A target item paired with the response entry it joined against by key. */
public record MatchedTarget(String key, ObjectNode owner, JsonNode responseEntry) {}
