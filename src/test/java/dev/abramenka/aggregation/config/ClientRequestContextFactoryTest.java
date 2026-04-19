package dev.abramenka.aggregation.config;

import static dev.abramenka.aggregation.model.ForwardedHeaders.CORRELATION_ID_HEADER;
import static dev.abramenka.aggregation.model.ForwardedHeaders.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .hasMessageContaining("'detokenize' must be either true or false");
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
