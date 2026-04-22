package dev.abramenka.aggregation.model;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class TraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";

    private static final Pattern TRACEPARENT =
            Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private TraceContext() {}

    public static @Nullable String traceIdFromTraceparent(@Nullable String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            return null;
        }
        String version = matcher.group(1);
        String traceId = matcher.group(2);
        String parentId = matcher.group(3);
        if ("ff".equals(version)
                || traceId.chars().allMatch(ch -> ch == '0')
                || parentId.chars().allMatch(ch -> ch == '0')) {
            return null;
        }
        return traceId;
    }

    public static String newTraceparent() {
        return "00-" + newTraceId() + "-" + newParentId() + "-01";
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String newParentId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
