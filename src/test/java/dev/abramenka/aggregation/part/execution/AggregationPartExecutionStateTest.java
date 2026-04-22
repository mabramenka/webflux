package dev.abramenka.aggregation.part.execution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abramenka.aggregation.model.AggregationContext;
import dev.abramenka.aggregation.model.AggregationPart;
import dev.abramenka.aggregation.model.AggregationPartResult;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

class AggregationPartExecutionStateTest {

    @Test
    void missingDependencies_returnsOnlyDependenciesThatWereNotApplied() {
        AggregationPartExecutionState state = new AggregationPartExecutionState();
        state.markApplied(part("account"));

        assertThat(state.missingDependencies(part("beneficialOwners", "account", "owners")))
                .containsExactly("owners");
    }

    @Test
    void missingDependencies_isEmptyWhenAllDependenciesWereApplied() {
        AggregationPartExecutionState state = new AggregationPartExecutionState();
        state.markApplied(part("account"));
        state.markApplied(part("owners"));

        assertThat(state.missingDependencies(part("beneficialOwners", "account", "owners")))
                .isEmpty();
    }

    private static AggregationPart part(String name, String... dependencies) {
        return new AggregationPart() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> dependencies() {
                return Set.of(dependencies);
            }

            @Override
            public Mono<AggregationPartResult> execute(ObjectNode rootSnapshot, AggregationContext context) {
                return Mono.empty();
            }
        };
    }
}
