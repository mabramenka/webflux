package dev.abramenka.aggregation.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.ApiVersionConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
class WebFluxConfig implements WebFluxConfigurer {

    private final ServerClientRequestContextArgumentResolver clientRequestContextArgumentResolver;

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(clientRequestContextArgumentResolver);
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .usePathSegment(1, path -> path.value().startsWith("/api/"))
                .addSupportedVersions("1")
                .setVersionRequired(true);
    }
}
