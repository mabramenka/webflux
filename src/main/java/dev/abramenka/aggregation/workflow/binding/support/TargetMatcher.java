package dev.abramenka.aggregation.workflow.binding.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Joins extracted targets against an indexed response. Unmatched targets are dropped. */
public final class TargetMatcher {

    public List<MatchedTarget> match(List<ExtractedTarget> targets, Map<String, JsonNode> indexedResponse) {
        List<MatchedTarget> matches = new ArrayList<>();
        for (ExtractedTarget target : targets) {
            JsonNode entry = indexedResponse.get(target.key());
            if (entry != null) {
                matches.add(new MatchedTarget(target.key(), target.owner(), entry));
            }
        }
        return matches;
    }
}
