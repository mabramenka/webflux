package com.example.aggregation.config;

import com.example.aggregation.client.AccountGroups;
import com.example.aggregation.client.Accounts;
import com.example.aggregation.client.ClientRequestContextArgumentResolver;
import com.example.aggregation.client.DownstreamClientErrorFilter;
import com.example.aggregation.client.Owners;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "account-group",
    types = AccountGroups.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT
)
@ImportHttpServices(
    group = "account",
    types = Accounts.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT
)
@ImportHttpServices(
    group = "owners",
    types = Owners.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT
)
public class HttpServiceClientConfig {

    @Bean
    WebClientHttpServiceGroupConfigurer downstreamHttpServiceConfigurer() {
        ClientRequestContextArgumentResolver resolver = new ClientRequestContextArgumentResolver();
        return groups -> groups.forEachGroup((group, clientBuilder, factoryBuilder) -> {
            factoryBuilder.customArgumentResolver(resolver);
            clientBuilder.filter(DownstreamClientErrorFilter.forClient(clientName(group.name())));
        });
    }

    private String clientName(String groupName) {
        return switch (groupName) {
            case "account-group" -> "Account group";
            case "account" -> "Account";
            case "owners" -> "Owners";
            default -> groupName;
        };
    }
}
