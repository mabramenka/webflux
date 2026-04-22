package dev.abramenka.aggregation.part;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

public interface AggregationDocumentPart extends AggregationPart {

    Mono<Void> apply(ObjectNode root, AggregationContext context);

    @Override
    default Mono<AggregationPartResult> execute(ObjectNode rootSnapshot, AggregationContext context) {
        ObjectNode workingRoot = rootSnapshot.deepCopy();
        return apply(workingRoot, context)
                .then(Mono.fromSupplier(() -> AggregationPartResult.patch(name(), rootSnapshot, workingRoot)));
    }
}
