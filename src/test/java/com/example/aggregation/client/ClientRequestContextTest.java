package com.example.aggregation.client;

import static org.assertj.core.api.Assertions.assertThat;

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
    void from_omitsMissingOrBlankDetokenizeQueryParam() {
        assertThat(ClientRequestContext.from(new HttpHeaders(), new LinkedMultiValueMap<>()).detokenize()).isNull();

        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", " ");

        ClientRequestContext request = ClientRequestContext.from(new HttpHeaders(), queryParams);

        assertThat(request.detokenize()).isNull();
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
