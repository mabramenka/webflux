package dev.abramenka.aggregation.part.execution;

import dev.abramenka.aggregation.model.AggregationPart;
import java.util.List;

record AggregationPartExecutionPlan(List<List<AggregationPart>> levels) {}
