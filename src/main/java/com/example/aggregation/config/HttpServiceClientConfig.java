package com.example.aggregation.config;

import com.example.aggregation.client.AccountGroups;
import com.example.aggregation.client.Accounts;
import com.example.aggregation.client.ClientRequestContextArgumentResolver;
import com.example.aggregation.client.DownstreamClientErrorFilter;
import com.example.aggregation.client.HttpServiceGroups;
import com.example.aggregation.client.Owners;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = HttpServiceGroups.ACCOUNT_GROUP,
    types = AccountGroups.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT
)
@ImportHttpServices(
    group = HttpServiceGroups.ACCOUNT,
    types = Accounts.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT
)
@ImportHttpServices(
    group = HttpServiceGroups.OWNERS,
    types = Owners.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT
)
public class HttpServiceClientConfig {

    @Bean
    WebClientHttpServiceGroupConfigurer downstreamHttpServiceConfigurer(MeterRegistry meterRegistry) {
        ClientRequestContextArgumentResolver resolver = new ClientRequestContextArgumentResolver();
        return groups -> groups.forEachGroup((group, clientBuilder, factoryBuilder) -> {
            factoryBuilder.customArgumentResolver(resolver);
            clientBuilder.filter(DownstreamClientErrorFilter.forClient(
                HttpServiceGroups.downstreamMetricClientName(group.name()),
                meterRegistry
            ));
        });
    }
}
