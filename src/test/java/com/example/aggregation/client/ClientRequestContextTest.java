package com.example.aggregation.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

class ClientRequestContextTest {

    @Test
    void from_preservesFalseDetokenizeQueryParam() {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", "false");

        ClientRequestContext request = ClientRequestContext.from(new HttpHeaders(), queryParams);

        assertThat(request.detokenize()).isFalse();
        assertThat(request.applyQueryParams(UriComponentsBuilder.fromPath("/account-groups")).build()).hasToString("/account-groups?detokenize=false");
    }

    @Test
    void from_omitsMissingOrBlankDetokenizeQueryParam() {
        assertThat(ClientRequestContext.from(new HttpHeaders(), new LinkedMultiValueMap<>()).detokenize()).isNull();

        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", " ");

        ClientRequestContext request = ClientRequestContext.from(new HttpHeaders(), queryParams);

        assertThat(request.detokenize()).isNull();
        assertThat(request.applyQueryParams(UriComponentsBuilder.fromPath("/account-groups")).build()).hasToString("/account-groups");
    }
}
