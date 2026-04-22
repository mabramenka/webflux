package dev.abramenka.aggregation.error;

import org.jspecify.annotations.Nullable;

public final class EnrichmentDependencyException extends FacadeException {

    private EnrichmentDependencyException(
            ProblemCatalog catalog, @Nullable String dependency, @Nullable Throwable cause) {
        super(catalog, dependency, cause);
    }

    public static EnrichmentDependencyException contractViolation(String partName, @Nullable Throwable cause) {
        return new EnrichmentDependencyException(ProblemCatalog.ENRICH_CONTRACT_VIOLATION, dependency(partName), cause);
    }

    private static @Nullable String dependency(String partName) {
        return switch (partName) {
            case "account" -> "enricher:account";
            case "owners", "beneficialOwners" -> "enricher:owners";
            default -> null;
        };
    }
}
