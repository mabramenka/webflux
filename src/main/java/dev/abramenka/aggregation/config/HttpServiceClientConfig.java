package dev.abramenka.aggregation.config;

import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.client.ClientRequestContextHttpServiceArgumentResolver;
import dev.abramenka.aggregation.client.DownstreamClientErrorFilter;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.webclient.autoconfigure.WebClientSsl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@ImportHttpServices(
        group = HttpServiceGroups.ACCOUNT_GROUP,
        types = AccountGroups.class,
        clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
@ImportHttpServices(
        group = HttpServiceGroups.ACCOUNT,
        types = Accounts.class,
        clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
@ImportHttpServices(
        group = HttpServiceGroups.OWNERS,
        types = Owners.class,
        clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
class HttpServiceClientConfig {

    private static final String OUTBOUND_BUNDLE = "downstream";

    private final ClientRequestContextHttpServiceArgumentResolver clientRequestContextArgumentResolver;
    private final WebClientSsl webClientSsl;

    @Bean
    WebClientHttpServiceGroupConfigurer downstreamHttpServiceConfigurer() {
        return groups -> groups.forEachGroup((group, clientBuilder, factoryBuilder) -> {
            factoryBuilder.customArgumentResolver(clientRequestContextArgumentResolver);
            clientBuilder.filter(
                    DownstreamClientErrorFilter.forClient(HttpServiceGroups.downstreamClientName(group.name())));
            // Absent bundle → plain HTTP, so tests/dev can run without certs; https:// calls would
            // then fail at handshake time, which is the correct visible failure mode.
            try {
                webClientSsl.fromBundle(OUTBOUND_BUNDLE).accept(clientBuilder);
            } catch (NoSuchSslBundleException ignored) {
                // no bundle configured — skip SSL customization
            }
        });
    }
}
