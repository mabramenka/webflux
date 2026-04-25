package dev.abramenka.aggregation.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JsonPointerBuilderTest {

    @Test
    void build_emptyBuilderReturnsRootPointer() {
        assertThat(JsonPointerBuilder.create().build()).isEmpty();
    }

    @Test
    void build_composesFieldsAndIndices() {
        String pointer = JsonPointerBuilder.create()
                .field("data")
                .index(0)
                .field("account1")
                .build();

        assertThat(pointer).isEqualTo("/data/0/account1");
    }

    @Test
    void build_appendsArrayDashToken() {
        String pointer = JsonPointerBuilder.create()
                .field("data")
                .index(0)
                .field("account1")
                .append()
                .build();

        assertThat(pointer).isEqualTo("/data/0/account1/-");
    }

    @Test
    void build_escapesTildeBeforeSlash() {
        String pointer = JsonPointerBuilder.create().field("a~b/c").build();

        // Per RFC 6901: tilde becomes ~0 first, then forward-slash becomes ~1.
        assertThat(pointer).isEqualTo("/a~0b~1c");
    }

    @Test
    void build_escapesOnlyWhenNeeded() {
        String pointer = JsonPointerBuilder.create().field("plain").build();

        assertThat(pointer).isEqualTo("/plain");
    }

    @Test
    void build_roundTripsThroughJsonPointer() {
        String raw = JsonPointerBuilder.create()
                .field("data")
                .index(2)
                .field("foo/bar")
                .field("baz~qux")
                .build();

        JsonPointer parsed = JsonPointer.parse(raw);

        assertThat(parsed.segments()).containsExactly("data", "2", "foo/bar", "baz~qux");
    }

    @Test
    void index_rejectsNegativeValues() {
        assertThatThrownBy(() -> JsonPointerBuilder.create().index(-1))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("non-negative");
    }
}
