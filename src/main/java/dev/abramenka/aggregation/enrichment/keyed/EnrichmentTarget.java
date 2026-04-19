package dev.abramenka.aggregation.enrichment.keyed;

import tools.jackson.databind.node.ObjectNode;

record EnrichmentTarget(String key, ObjectNode node) {}
