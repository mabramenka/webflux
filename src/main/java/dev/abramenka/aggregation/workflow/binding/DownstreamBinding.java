package dev.abramenka.aggregation.workflow.binding;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One REST dependency inside an aggregation part.
 *
 * <p>A binding owns its own key extraction so different REST dependencies in the same part may pull
 * different keys from the same source document. A binding must produce a named step result, a
 * patch fragment, or both — a binding that produces nothing is rejected at construction.
 */
public record DownstreamBinding(
        BindingName name,
        KeyExtractionRule keyExtraction,
        DownstreamCall downstreamCall,
        ResponseIndexingRule responseIndexing,
        @Nullable String storeAs,
        @Nullable WriteRule writeRule) {

    public DownstreamBinding {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(keyExtraction, "keyExtraction");
        Objects.requireNonNull(downstreamCall, "downstreamCall");
        Objects.requireNonNull(responseIndexing, "responseIndexing");
        if (storeAs != null && storeAs.isBlank()) {
            throw new IllegalArgumentException("storeAs must not be blank when set");
        }
        if (storeAs == null && writeRule == null) {
            throw new IllegalArgumentException("Binding '" + name
                    + "' must produce a step result (storeAs), a patch fragment (writeRule), or both");
        }
    }
}
