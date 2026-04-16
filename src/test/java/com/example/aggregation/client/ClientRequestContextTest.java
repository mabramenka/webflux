package com.example.aggregation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class ClientRequestContextTest {

    @Test
    void from_preservesFalseDetokenizeQueryParam() {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", "false");

        ClientRequestContext request = ClientRequestContext.from(new HttpHeaders(), queryParams);

        assertThat(request.detokenize()).isFalse();
    }

    @Test
    void from_omitsMissingDetokenizeQueryParam() {
        assertThat(ClientRequestContext.from(new HttpHeaders(), new LinkedMultiValueMap<>()).detokenize()).isNull();
    }

    @Test
    void from_rejectsBlankDetokenizeQueryParam() {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", " ");

        assertThatThrownBy(() -> ClientRequestContext.from(new HttpHeaders(), queryParams))
            .hasMessageContaining("'detokenize' must be either true or false");
    }

    @Test
    void from_preservesForwardedHeadersAsMap() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("abc");
        headers.set("X-Request-Id", "req-123");
        headers.set("X-Correlation-Id", "corr-456");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US");

        ClientRequestContext request = ClientRequestContext.from(headers, new LinkedMultiValueMap<>());

        assertThat(request.headers().asMap())
            .containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc")
            .containsEntry("X-Request-Id", "req-123")
            .containsEntry("X-Correlation-Id", "corr-456")
            .containsEntry(HttpHeaders.ACCEPT_LANGUAGE, "en-US");
    }
}
