/**
 * Aggregation enrichments applied by the aggregation part planner.
 *
 * <p>To add a new enrichment, create a package under {@code enrichment.<name>} with a Spring
 * {@code @Component} implementing {@code AggregationPart} or {@code AggregationEnrichment} from
 * {@code dev.abramenka.aggregation.model}. The component {@code name()} is the public {@code include}
 * value. Declare {@code dependencies()} when the enrichment needs data produced by another enrichment.
 *
 * <p>Reusable implementation helpers live under {@code enrichment.support} and should not own
 * business-specific field names.
 */
@NullMarked
package dev.abramenka.aggregation.enrichment;

import org.jspecify.annotations.NullMarked;
