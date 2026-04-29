package dev.abramenka.aggregation.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed RFC 6901 JSON Pointer. Internal helper for the patch model — operations carry raw
 * pointer strings, this class is used by the applicator to navigate.
 */
final class JsonPointer {

    private static final JsonPointer ROOT = new JsonPointer("", List.of());

    private final String raw;
    private final List<String> segments;

    private JsonPointer(String raw, List<String> segments) {
        this.raw = raw;
        this.segments = List.copyOf(segments);
    }

    public static JsonPointer parse(String raw) {
        if (raw.isEmpty()) {
            return ROOT;
        }
        if (raw.charAt(0) != '/') {
            throw new JsonPatchException("JSON Pointer must be empty or start with '/': '" + raw + "'");
        }
        String[] tokens = raw.substring(1).split("/", -1);
        List<String> parsed = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            parsed.add(unescape(token));
        }
        return new JsonPointer(raw, parsed);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public List<String> segments() {
        return segments;
    }

    public String lastSegment() {
        if (segments.isEmpty()) {
            throw new JsonPatchException("Root pointer has no last segment");
        }
        return segments.getLast();
    }

    public List<String> parentSegments() {
        if (segments.isEmpty()) {
            throw new JsonPatchException("Root pointer has no parent");
        }
        return segments.subList(0, segments.size() - 1);
    }

    @Override
    public String toString() {
        return raw;
    }

    private static String unescape(String token) {
        if (token.indexOf('~') < 0) {
            return token;
        }
        StringBuilder result = new StringBuilder(token.length());
        int index = 0;
        while (index < token.length()) {
            char c = token.charAt(index);
            if (c != '~') {
                result.append(c);
                index++;
                continue;
            }
            int nextIndex = index + 1;
            if (nextIndex >= token.length()) {
                throw new JsonPatchException("Invalid JSON Pointer escape in token '" + token + "'");
            }
            char next = token.charAt(nextIndex);
            switch (next) {
                case '0' -> result.append('~');
                case '1' -> result.append('/');
                default ->
                    throw new JsonPatchException(
                            "Invalid JSON Pointer escape '~" + next + "' in token '" + token + "'");
            }
            index += 2;
        }
        return result.toString();
    }
}
