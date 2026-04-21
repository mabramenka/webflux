package dev.abramenka.aggregation.error;

import org.jspecify.annotations.Nullable;

record ValidationError(String location, @Nullable String field, String message) {}
