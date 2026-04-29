package dev.abramenka.aggregation.api;

import dev.abramenka.aggregation.service.AggregateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
abstract class AggregateControllerWebFluxTestSupport {

    protected static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    protected static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-00";

    @Autowired
    protected WebTestClient webTestClient;

    @MockitoBean
    protected AggregateService aggregateService;

    @Autowired
    protected ObjectMapper objectMapper;
}
