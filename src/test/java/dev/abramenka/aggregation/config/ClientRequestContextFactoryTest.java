package dev.abramenka.aggregation.config;

import static dev.abramenka.aggregation.model.ForwardedHeaders.CORRELATION_ID_HEADER;
import static dev.abramenka.aggregation.model.ForwardedHeaders.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abramenka.aggregation.error.RequestValidationException;
import dev.abramenka.aggregation.model.ClientRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class ClientRequestContextFactoryTest {

    private final ClientRequestContextFactory factory = new ClientRequestContextFactory();

    @Test
    void from_preservesFalseDetokenizeQueryParam() {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", "false");

        ClientRequestContext request = factory.from(new HttpHeaders(), queryParams);

        assertThat(request.detokenize()).isFalse();
    }

    @Test
    void from_omitsMissingDetokenizeQueryParam() {
        assertThat(factory.from(new HttpHeaders(), new LinkedMultiValueMap<>()).detokenize())
                .isNull();
    }

    @Test
    void from_rejectsBlankDetokenizeQueryParam() {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", " ");

        assertThatThrownBy(() -> factory.from(new HttpHeaders(), queryParams))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(error -> assertThat(
                                ((RequestValidationException) error).getBody().getProperties())
                        .containsKey("violations"));
    }

    @Test
    void from_parsesFieldsQueryParamIntoProjections() {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("fields", "aaa, bbb ,ccc");

        ClientRequestContext request = factory.from(new HttpHeaders(), queryParams);

        assertThat(request.projections().raw()).containsExactly("aaa", "bbb", "ccc");
        assertThat(request.projections().isEmpty()).isFalse();
    }

    @Test
    void from_treatsMissingOrBlankFieldsAsEmptyProjections() {
        assertThat(factory.from(new HttpHeaders(), new LinkedMultiValueMap<>())
                        .projections()
                        .isEmpty())
                .isTrue();

        MultiValueMap<String, String> blank = new LinkedMultiValueMap<>();
        blank.add("fields", " ");
        assertThat(factory.from(new HttpHeaders(), blank).projections().isEmpty())
                .isTrue();
    }

    @Test
    void from_preservesForwardedHeadersAsMap() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("abc");
        headers.set(REQUEST_ID_HEADER, "req-123");
        headers.set(CORRELATION_ID_HEADER, "corr-456");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US");

        ClientRequestContext request = factory.from(headers, new LinkedMultiValueMap<>());

        assertThat(request.headers().asMap())
                .containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc")
                .containsEntry(REQUEST_ID_HEADER, "req-123")
                .containsEntry(CORRELATION_ID_HEADER, "corr-456")
                .containsEntry(HttpHeaders.ACCEPT_LANGUAGE, "en-US");
    }
}
