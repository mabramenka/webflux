package dev.abramenka.aggregation.service;

import dev.abramenka.aggregation.model.AggregationPart;
import java.util.List;

record AggregationPartExecutionPlan(List<List<AggregationPart>> levels) {}
