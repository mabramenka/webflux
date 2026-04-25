package dev.abramenka.aggregation.api;

import dev.abramenka.aggregation.config.ClientRequestContextFactory;
import dev.abramenka.aggregation.config.MdcPropagationConfig;
import dev.abramenka.aggregation.config.RequestContextMdcFilter;
import dev.abramenka.aggregation.config.ServerClientRequestContextArgumentResolver;
import dev.abramenka.aggregation.config.WebFluxConfig;
import dev.abramenka.aggregation.error.AggregationErrorResponseAdvice;
import dev.abramenka.aggregation.service.AggregateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

@WebFluxTest(controllers = AggregateController.class)
@Import({
    AggregationErrorResponseAdvice.class,
    ClientRequestContextFactory.class,
    MdcPropagationConfig.class,
    RequestContextMdcFilter.class,
    ServerClientRequestContextArgumentResolver.class,
    WebFluxConfig.class
})
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
