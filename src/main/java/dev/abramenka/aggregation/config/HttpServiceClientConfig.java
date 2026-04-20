package dev.abramenka.aggregation.config;

import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.client.ClientRequestContextHttpServiceArgumentResolver;
import dev.abramenka.aggregation.client.DownstreamClientErrorFilter;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.webclient.autoconfigure.WebClientSsl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
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
public class HttpServiceClientConfig {

    private static final String OUTBOUND_BUNDLE = "downstream";

    private final ClientRequestContextHttpServiceArgumentResolver clientRequestContextArgumentResolver;
    private final WebClientSsl webClientSsl;
    private final SslBundles sslBundles;

    public HttpServiceClientConfig(
            ClientRequestContextHttpServiceArgumentResolver clientRequestContextArgumentResolver,
            WebClientSsl webClientSsl,
            SslBundles sslBundles) {
        this.clientRequestContextArgumentResolver = clientRequestContextArgumentResolver;
        this.webClientSsl = webClientSsl;
        this.sslBundles = sslBundles;
    }

    @Bean
    WebClientHttpServiceGroupConfigurer downstreamHttpServiceConfigurer() {
        return groups -> groups.forEachGroup((group, clientBuilder, factoryBuilder) -> {
            factoryBuilder.customArgumentResolver(clientRequestContextArgumentResolver);
            clientBuilder.filter(
                    DownstreamClientErrorFilter.forClient(HttpServiceGroups.downstreamClientName(group.name())));

            String bundleName = resolveBundleName();
            if (bundleName != null) {
                webClientSsl.fromBundle(bundleName).accept(clientBuilder);
            }
        });
    }

    private @Nullable String resolveBundleName() {
        // Treat an absent bundle as "plain HTTP" so tests/dev can run without provisioning certificates.
        // Any attempt to call https:// without a matching bundle will fail at request time with a
        // standard TLS handshake error, which is the correct, visible failure mode.
        try {
            sslBundles.getBundle(OUTBOUND_BUNDLE);
            return OUTBOUND_BUNDLE;
        } catch (NoSuchSslBundleException ignored) {
            return null;
        }
    }
}
