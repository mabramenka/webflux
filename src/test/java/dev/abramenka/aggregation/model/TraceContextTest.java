package dev.abramenka.aggregation.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void traceIdFromTraceparent_acceptsStrictW3cTraceparent() {
        assertThat(TraceContext.traceIdFromTraceparent("00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
                .isEqualTo(TRACE_ID);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-zz",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra"
            })
    void traceIdFromTraceparent_rejectsInvalidTraceparent(String traceparent) {
        assertThat(TraceContext.traceIdFromTraceparent(traceparent)).isNull();
    }
}
