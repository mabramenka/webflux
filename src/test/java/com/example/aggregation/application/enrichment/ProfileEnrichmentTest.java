package com.example.aggregation.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.aggregation.application.AggregationContext;
import com.example.aggregation.application.RequestedEnrichments;
import com.example.aggregation.downstream.DownstreamHeaders;
import com.example.aggregation.downstream.DownstreamRequest;
import com.example.aggregation.downstream.WebClientProfileClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ProfileEnrichmentTest {

    @Test
    void supports_returnsFalseWhenMainResponseHasNoCustomerId() {
        ProfileEnrichment enrichment = new ProfileEnrichment(mock(WebClientProfileClient.class));
        AggregationContext context = new AggregationContext(
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode().put("customerId", " "),
            new DownstreamRequest(DownstreamHeaders.builder().build(), null),
            new RequestedEnrichments(true, java.util.Set.of())
        );

        assertThat(enrichment.supports(context)).isFalse();
    }
}
