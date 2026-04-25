package dev.abramenka.aggregation.enrichment.beneficialowners;

import tools.jackson.databind.node.ArrayNode;

record ResolvedEntity(int dataIndex, int ownerIndex, ArrayNode details) {}
