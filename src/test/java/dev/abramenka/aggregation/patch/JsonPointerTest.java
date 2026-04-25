package dev.abramenka.aggregation.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JsonPointerTest {

    @Test
    void parse_emptyStringIsRoot() {
        JsonPointer pointer = JsonPointer.parse("");

        assertThat(pointer.isRoot()).isTrue();
        assertThat(pointer.segments()).isEmpty();
    }

    @Test
    void parse_splitsAndUnescapesSegments() {
        JsonPointer pointer = JsonPointer.parse("/data/0/foo~1bar/baz~0qux");

        assertThat(pointer.isRoot()).isFalse();
        assertThat(pointer.segments()).containsExactly("data", "0", "foo/bar", "baz~qux");
        assertThat(pointer.lastSegment()).isEqualTo("baz~qux");
        assertThat(pointer.parentSegments()).containsExactly("data", "0", "foo/bar");
    }

    @Test
    void parse_supportsTrailingEmptySegment() {
        JsonPointer pointer = JsonPointer.parse("/data/-");

        assertThat(pointer.lastSegment()).isEqualTo("-");
        assertThat(pointer.parentSegments()).containsExactly("data");
    }

    @Test
    void parse_rejectsMissingLeadingSlash() {
        assertThatThrownBy(() -> JsonPointer.parse("foo/bar"))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("must be empty or start with '/'");
    }

    @Test
    void parse_rejectsInvalidEscape() {
        assertThatThrownBy(() -> JsonPointer.parse("/foo~2bar"))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("Invalid JSON Pointer escape");
    }

    @Test
    void parse_rejectsDanglingTilde() {
        assertThatThrownBy(() -> JsonPointer.parse("/foo~"))
                .isInstanceOf(JsonPatchException.class)
                .hasMessageContaining("Invalid JSON Pointer escape");
    }

    @Test
    void rootPointer_hasNoLastSegmentOrParent() {
        JsonPointer root = JsonPointer.parse("");

        assertThatThrownBy(root::lastSegment).isInstanceOf(JsonPatchException.class);
        assertThatThrownBy(root::parentSegments).isInstanceOf(JsonPatchException.class);
    }
}
