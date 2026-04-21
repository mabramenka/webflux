package dev.abramenka.aggregation.postprocessor;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class BeneficialOwnersResolutionException extends RuntimeException {

    enum Reason {
        DEPTH_EXCEEDED,
        DOWNSTREAM_FAILED,
        MALFORMED_RESPONSE
    }

    private final Reason reason;

    BeneficialOwnersResolutionException(Reason reason, String message) {
        this(reason, message, null);
    }

    BeneficialOwnersResolutionException(Reason reason, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    Reason reason() {
        return reason;
    }
}
