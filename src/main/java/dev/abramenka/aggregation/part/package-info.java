/**
 * Aggregation part planning and execution engine.
 *
 * <p>The public entry points are {@code AggregationPartPlanner} and {@code AggregationPartExecutor}.
 * Engine internals such as graph construction, level planning, metrics, result application, and
 * execution state stay package-private.
 */
@NullMarked
package dev.abramenka.aggregation.part;

import org.jspecify.annotations.NullMarked;
