package dev.abramenka.aggregation.patch;

/**
 * Options that influence how a {@link JsonPatchBuilder} composes operations. Reserved for future
 * helper methods (e.g. ensure-path that emits intermediate {@code add} ops); the default form is
 * conservative and never invents structure the caller did not explicitly request.
 */
public record PatchWriteOptions(boolean createMissingIntermediates) {

    public static final PatchWriteOptions DEFAULT = new PatchWriteOptions(false);
}
