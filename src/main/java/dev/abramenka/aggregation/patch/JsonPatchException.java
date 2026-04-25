package dev.abramenka.aggregation.patch;

public final class JsonPatchException extends RuntimeException {

    public JsonPatchException(String message) {
        super(message);
    }

    public JsonPatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
