package dev.abramenka.aggregation.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class AggregateControllerWebFluxSliceConfig {

    @Bean
    ClientRequestContextFactory clientRequestContextFactory() {
        return new ClientRequestContextFactory();
    }

    @Bean
    ServerClientRequestContextArgumentResolver serverClientRequestContextArgumentResolver(
            ClientRequestContextFactory clientRequestContextFactory) {
        return new ServerClientRequestContextArgumentResolver(clientRequestContextFactory);
    }

    @Bean
    RequestContextMdcFilter requestContextMdcFilter() {
        return new RequestContextMdcFilter();
    }
}
