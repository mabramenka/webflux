package com.example.aggregation.downstream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

class DownstreamRequestTest {

    @Test
    void from_preservesFalseDetokenizeQueryParam() {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", "false");

        DownstreamRequest request = DownstreamRequest.from(new HttpHeaders(), queryParams);

        assertThat(request.detokenize()).isFalse();
        assertThat(request.applyQueryParams(UriComponentsBuilder.fromPath("/main")).build()).hasToString("/main?detokenize=false");
    }

    @Test
    void from_omitsMissingOrBlankDetokenizeQueryParam() {
        assertThat(DownstreamRequest.from(new HttpHeaders(), new LinkedMultiValueMap<>()).detokenize()).isNull();

        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        queryParams.add("detokenize", " ");

        DownstreamRequest request = DownstreamRequest.from(new HttpHeaders(), queryParams);

        assertThat(request.detokenize()).isNull();
        assertThat(request.applyQueryParams(UriComponentsBuilder.fromPath("/main")).build()).hasToString("/main");
    }
}
