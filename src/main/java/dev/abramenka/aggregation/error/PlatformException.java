package dev.abramenka.aggregation.error;

import org.jspecify.annotations.Nullable;

public final class PlatformException extends FacadeException {

    private PlatformException(ProblemCatalog catalog, @Nullable Throwable cause) {
        super(catalog, null, cause);
    }

    public static PlatformException overloaded(Throwable cause) {
        return new PlatformException(ProblemCatalog.PLATFORM_OVERLOADED, cause);
    }
}
