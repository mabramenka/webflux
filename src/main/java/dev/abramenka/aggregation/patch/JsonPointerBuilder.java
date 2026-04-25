package dev.abramenka.aggregation.patch;

/**
 * Fluent builder for RFC 6901 JSON Pointer strings. Field segments are escaped automatically
 * ({@code ~} -> {@code ~0}, {@code /} -> {@code ~1}). Array segments accept either an explicit
 * index or the {@code -} append token.
 */
public final class JsonPointerBuilder {

    private final StringBuilder pointer = new StringBuilder();

    private JsonPointerBuilder() {}

    public static JsonPointerBuilder create() {
        return new JsonPointerBuilder();
    }

    public JsonPointerBuilder field(String name) {
        pointer.append('/').append(escape(name));
        return this;
    }

    public JsonPointerBuilder index(int index) {
        if (index < 0) {
            throw new JsonPatchException("Array index must be non-negative: " + index);
        }
        pointer.append('/').append(index);
        return this;
    }

    public JsonPointerBuilder append() {
        pointer.append("/-");
        return this;
    }

    public String build() {
        return pointer.toString();
    }

    private static String escape(String token) {
        if (token.indexOf('~') < 0 && token.indexOf('/') < 0) {
            return token;
        }
        return token.replace("~", "~0").replace("/", "~1");
    }
}
