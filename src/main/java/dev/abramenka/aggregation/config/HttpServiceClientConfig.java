package dev.abramenka.aggregation.config;

import dev.abramenka.aggregation.client.AccountGroups;
import dev.abramenka.aggregation.client.Accounts;
import dev.abramenka.aggregation.client.ClientRequestContextHttpServiceArgumentResolver;
import dev.abramenka.aggregation.client.DownstreamClientErrorFilter;
import dev.abramenka.aggregation.client.HttpServiceGroups;
import dev.abramenka.aggregation.client.Owners;
import java.util.Map;
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

    /**
     * Per-group outbound TLS bundle names. Maps a Spring HTTP service group to a {@code spring.ssl.bundle.*}
     * name that supplies the outbound trust material. A shared {@code downstream} bundle is used today;
     * if any downstream ever needs its own CA, switch the mapping to distinct bundle names without
     * touching this class's wiring.
     */
    private static final Map<String, String> OUTBOUND_BUNDLE_BY_GROUP = Map.of(
            HttpServiceGroups.ACCOUNT_GROUP, "downstream",
            HttpServiceGroups.ACCOUNT, "downstream",
            HttpServiceGroups.OWNERS, "downstream");

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

            String bundleName = resolveBundleName(group.name());
            if (bundleName != null) {
                webClientSsl.fromBundle(bundleName).accept(clientBuilder);
            }
        });
    }

    private @Nullable String resolveBundleName(String groupName) {
        String bundleName = OUTBOUND_BUNDLE_BY_GROUP.get(groupName);
        if (bundleName == null) {
            return null;
        }
        // Treat an absent bundle as "plain HTTP" so tests/dev can run without provisioning certificates.
        // Any attempt to call https:// without a matching bundle will fail at request time with a
        // standard TLS handshake error, which is the correct, visible failure mode.
        try {
            sslBundles.getBundle(bundleName);
            return bundleName;
        } catch (NoSuchSslBundleException ignored) {
            return null;
        }
    }
}
