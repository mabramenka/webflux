package dev.abramenka.aggregation.enrichment.beneficialowners;

import tools.jackson.databind.node.ObjectNode;

record RootEntityTarget(int dataIndex, int ownerIndex, ObjectNode node) {}
