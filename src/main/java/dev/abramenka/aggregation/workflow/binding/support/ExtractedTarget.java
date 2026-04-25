package dev.abramenka.aggregation.workflow.binding.support;

import tools.jackson.databind.node.ObjectNode;

/** A key extracted by {@link KeyExtractor} together with the source item that produced it. */
public record ExtractedTarget(String key, ObjectNode owner) {}
