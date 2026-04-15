package com.example.aggregation.service.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.aggregation.client.ProfileClient;
import com.example.aggregation.service.AggregationContext;
import com.example.aggregation.service.RequestedParts;
import com.example.aggregation.web.DownstreamHeaders;
import com.example.aggregation.web.DownstreamRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ProfilePartTest {

    @Test
    void supports_returnsFalseWhenMainResponseHasNoCustomerId() {
        ProfilePart part = new ProfilePart(mock(ProfileClient.class));
        AggregationContext context = new AggregationContext(
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode().put("customerId", " "),
            new DownstreamRequest(DownstreamHeaders.builder().build(), null),
            new RequestedParts(true, java.util.Set.of())
        );

        assertThat(part.supports(context)).isFalse();
    }
}
